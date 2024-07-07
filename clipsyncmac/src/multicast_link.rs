use std::{
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket},
    sync::{
        mpsc::{self, Receiver, Sender},
        Arc, RwLock,
    },
    thread::{self, JoinHandle},
    time::Duration,
};

use clipboard_rs::{Clipboard, ClipboardContent, ClipboardContext};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

pub const PORT: u16 = 7645;

lazy_static::lazy_static! {
    pub static ref IPV4: IpAddr = Ipv4Addr::new(224, 0, 0, 123).into();
    pub static ref IPV6: IpAddr = Ipv6Addr::new(0xFF02, 0, 0, 0, 0, 0, 0, 0x123).into();
}

fn new_socket(addr: &SocketAddr) -> Socket {
    let domain = Domain::for_address(*addr);

    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP)).unwrap();

    socket
        .set_read_timeout(Some(Duration::from_millis(100)))
        .unwrap();

    socket
}

pub enum IpType {
    IPV4,
    #[allow(dead_code)]
    IPV6,
}

pub struct MulticastLink {
    sender_socket: Option<UdpSocket>,
    ip_type: IpType,
    last_pasted: Arc<RwLock<String>>,
    server_signal: Sender<bool>,
}

impl MulticastLink {
    pub fn new(ip_type: IpType, last_pasted: Arc<RwLock<String>>) -> Self {
        let (tx, rx) = mpsc::channel::<bool>();

        let addr = match ip_type {
            IpType::IPV4 => SocketAddr::new(*IPV4, PORT),
            IpType::IPV6 => SocketAddr::new(*IPV6, PORT),
        };

        let lp = last_pasted.clone();
        // Server creation
        build_udp_server(addr, rx, lp);

        // Sender
        let sender_socket = new_sender_socket(addr);

        Self {
            sender_socket: Some(sender_socket.into()),
            ip_type,
            server_signal: tx,
            last_pasted,
        }
    }

    pub fn send_data(&self, data: String) {
        if let Some(sender_socket) = &self.sender_socket {
            let addr = match self.ip_type {
                IpType::IPV4 => SocketAddr::new(*IPV4, PORT),
                IpType::IPV6 => SocketAddr::new(*IPV6, PORT),
            };

            sender_socket.send_to(data.as_bytes(), &addr).unwrap();
        }
    }

    pub fn create(&mut self) {
        let (tx, rx) = mpsc::channel::<bool>();

        let addr = match self.ip_type {
            IpType::IPV4 => SocketAddr::new(*IPV4, PORT),
            IpType::IPV6 => SocketAddr::new(*IPV6, PORT),
        };

        let lp = self.last_pasted.clone();
        // Server creation
        build_udp_server(addr, rx, lp);

        // Sender
        let sender_socket = new_sender_socket(addr);

        self.sender_socket = Some(sender_socket.into());
        self.server_signal = tx;
    }

    pub fn dispose(&mut self) {
        if self.server_signal.send(true).is_err() {
            eprintln!(
                "Could not send shut down signal to the server. Server might already be closed"
            );
        }

        self.sender_socket = None;
    }
}

fn build_udp_server(
    addr: SocketAddr,
    rx: Receiver<bool>,
    lp: Arc<RwLock<String>>,
) -> JoinHandle<()> {
    thread::Builder::new()
        .name("Server".to_string())
        .spawn(move || {
            let socket = new_socket(&addr);

            // Joining Multicast Group
            match addr.ip() {
                IpAddr::V4(ref v4) => {
                    socket
                        .join_multicast_v4(v4, &Ipv4Addr::new(0, 0, 0, 0))
                        .unwrap();
                    socket.set_multicast_loop_v4(false).unwrap();
                }
                IpAddr::V6(ref v6) => {
                    socket.join_multicast_v6(v6, 0).unwrap();
                    socket.set_only_v6(true).unwrap();
                    socket.set_multicast_loop_v6(false).unwrap();
                }
            };

            socket.bind(&SockAddr::from(addr)).unwrap();

            println!("Server joined multicast address: {addr}");
            println!("Server ready");

            let listener: UdpSocket = socket.into();

            loop {
                let mut buf = [0u8; 1024];

                if let Ok(shut_down) = rx.try_recv() {
                    if shut_down {
                        match addr.ip() {
                            IpAddr::V4(ref v4) => {
                                listener
                                    .leave_multicast_v4(v4, &Ipv4Addr::new(0, 0, 0, 0))
                                    .unwrap();
                            }
                            IpAddr::V6(ref v6) => {
                                listener.leave_multicast_v6(v6, 0).unwrap();
                            }
                        };

                        break;
                    }
                }

                match listener.recv_from(&mut buf) {
                    Ok((len, remote_addr)) => {
                        println!("Received data from: {remote_addr}",);

                        let data = String::from_utf8_lossy(&buf[..len]).to_string();

                        if let Ok(clipboard_ctx) = ClipboardContext::new() {
                            if clipboard_ctx
                                .set(vec![ClipboardContent::Text(data.clone())])
                                .is_err()
                            {
                                println!("Could not set clipboard data");
                                continue;
                            }

                            if let Ok(mut lp) = lp.try_write() {
                                *lp = data;
                            }

                            println!("Pasted to clipboard successfully");
                        }
                    }
                    _ => {
                        // Look for specific resource not found error and skip it
                    }
                }
            }

            println!("Shutting down server");
        })
        .unwrap()
}

fn new_sender_socket(addr: SocketAddr) -> Socket {
    let sender_socket = new_socket(&addr);

    if addr.is_ipv4() {
        sender_socket
            .set_multicast_if_v4(&Ipv4Addr::new(0, 0, 0, 0))
            .unwrap();

        sender_socket
            .bind(&SockAddr::from(SocketAddr::new(
                Ipv4Addr::new(0, 0, 0, 0).into(),
                0,
            )))
            .unwrap();

        sender_socket.set_multicast_loop_v4(false).unwrap();
    } else {
        // NOTE: Specific to my machine
        sender_socket.set_multicast_if_v6(15).unwrap();

        sender_socket
            .bind(&SockAddr::from(SocketAddr::new(
                Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 0).into(),
                0,
            )))
            .unwrap();

        sender_socket.set_multicast_loop_v6(false).unwrap();
    }

    sender_socket
}
