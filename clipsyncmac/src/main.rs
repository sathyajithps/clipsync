use std::{
    io,
    mem::MaybeUninit,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Barrier,
    },
    thread::{self, JoinHandle},
    time::Duration,
};

use lazy_static::lazy_static;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

pub const PORT: u16 = 7645;

lazy_static! {
    pub static ref IPV4: IpAddr = Ipv4Addr::new(224, 0, 0, 123).into();
    pub static ref IPV6: IpAddr = Ipv6Addr::new(0xFF02, 0, 0, 0, 0, 0, 0, 0x123).into();
}

fn multicast_listener(
    response: &'static str,
    client_done: Arc<AtomicBool>,
    addr: SocketAddr,
) -> JoinHandle<()> {
    let server_barrier = Arc::new(Barrier::new(2));
    let client_barrier = Arc::clone(&server_barrier);

    let join_handle = thread::Builder::new()
        .name(format!("{response}:server"))
        .spawn(move || {
            let listener = join_multicast_addr(addr).expect("Failed to create listener");

            println!("{response}:server: joined: {addr}");

            server_barrier.wait();

            println!("{response}:server: is ready");

            while !client_done.load(Ordering::Relaxed) {
                let mut buf = [0u8; 64];

                match listener.recv_from(&mut buf) {
                    Ok((len, remote_addr)) => {
                        let data = &buf[..len];

                        println!(
                            "{response}:server: got data: {} from: {remote_addr}",
                            String::from_utf8_lossy(data)
                        );

                        let responder: UdpSocket = new_socket(&remote_addr)
                            .expect("failed to create responder")
                            .into();

                        responder
                            .send_to(response.as_bytes(), &remote_addr)
                            .expect("failed to respond");

                        println!("{response}:server: sent response to: {remote_addr}");
                    }
                    Err(err) => {
                        eprintln!("{response}:server: got an error: {err}");
                    }
                }
            }

            println!("{response}:client: is done");
        })
        .unwrap();

    client_barrier.wait();

    join_handle
}

struct NotifyServer(Arc<AtomicBool>);
impl Drop for NotifyServer {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Release);
    }
}

fn new_socket(addr: &SocketAddr) -> io::Result<Socket> {
    let domain = Domain::for_address(*addr);

    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;

    socket.set_read_timeout(Some(Duration::from_millis(100)))?;

    Ok(socket)
}

fn join_multicast_addr(addr: SocketAddr) -> io::Result<UdpSocket> {
    let ip_addr = addr.ip();

    let socket = new_socket(&addr)?;
    match ip_addr {
        IpAddr::V4(ref mdns_v4) => {
            socket.join_multicast_v4(mdns_v4, &Ipv4Addr::new(0, 0, 0, 0))?;
            socket.set_multicast_loop_v4(false)?;
        }
        IpAddr::V6(ref mdns_v6) => {
            socket.join_multicast_v6(mdns_v6, 0)?;
            socket.set_only_v6(true)?;
            socket.set_multicast_loop_v6(false)?;
        }
    };

    socket.bind(&SockAddr::from(addr))?;

    Ok(socket.into())
}

fn new_sender(addr: &SocketAddr) -> io::Result<Socket> {
    let socket = new_socket(addr)?;

    if addr.is_ipv4() {
        socket.set_multicast_if_v4(&Ipv4Addr::new(0, 0, 0, 0))?;

        socket.bind(&SockAddr::from(SocketAddr::new(
            Ipv4Addr::new(0, 0, 0, 0).into(),
            0,
        )))?;
    } else {
        socket.set_multicast_if_v6(15)?;

        socket.bind(&SockAddr::from(SocketAddr::new(
            Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 0).into(),
            0,
        )))?;
    }

    Ok(socket)
}

fn multicast_listener_1(response: &'static str, addr: SocketAddr) -> JoinHandle<()> {
    let join_handle = thread::Builder::new()
        .name(format!("{response}:server"))
        .spawn(move || {
            let listener = join_multicast_addr(addr).expect("Failed to create listener");

            println!("{response}:server: joined: {addr}");

            println!("{response}:server: is ready");

            loop {
                let mut buf = [0u8; 64];

                match listener.recv_from(&mut buf) {
                    Ok((len, remote_addr)) => {
                        let data = &buf[..len];

                        println!(
                            "{response}:server: got data: {} from: {remote_addr}",
                            String::from_utf8_lossy(data)
                        );

                        let responder: UdpSocket = new_socket(&remote_addr)
                            .expect("failed to create responder")
                            .into();

                        responder
                            .send_to(response.as_bytes(), &remote_addr)
                            .expect("failed to respond");

                        println!("{response}:server: sent response to: {remote_addr}");
                    }
                    Err(_err) => {
                        // eprintln!("{response}:server: got an error: {err}");
                    }
                }

                thread::sleep(Duration::from_millis(100));
            }

            // println!("{response}:client: is done");
        })
        .unwrap();

    join_handle
}

fn main() {
    let addr = SocketAddr::new(*IPV4, PORT);
    multicast_listener_1("ipv4", addr);
    loop {
        thread::sleep(Duration::from_millis(200));
    }
}

fn test_multicast(test: &'static str, addr: IpAddr) {
    assert!(addr.is_multicast());
    let addr = SocketAddr::new(addr, PORT);

    let client_done = Arc::new(AtomicBool::new(false));
    let notify = NotifyServer(Arc::clone(&client_done));

    multicast_listener(test, client_done, addr);

    println!("{test}:client: running");

    let message = b"Hello from client!";

    let socket = new_sender(&addr).expect("couldnot create sender");

    socket
        .send_to(message, &SockAddr::from(addr))
        .expect("could not send_to!");

    let mut buf = [MaybeUninit::<u8>::uninit(); 64];

    match socket.recv_from(&mut buf) {
        Ok((len, _remote_addr)) => {
            let data = &buf[..len];

            unsafe {
                let mut l = [0u8; 64];
                for i in 0..len {
                    l[i] = data[i].assume_init();
                }

                let response = String::from_utf8_lossy(&l[..len]);
                println!("{test}:client: got data: {response}");
                assert_eq!(test, response);
            }
        }
        Err(err) => {
            println!("{}:client: had a problem: {}", test, err);
            assert!(false);
        }
    }

    drop(notify);
}

#[test]
fn test_ipv4_multicast() {
    test_multicast("ipv4", *IPV4);
}

#[test]
fn test_ipv6_multicast() {
    test_multicast("ipv6", *IPV6);
}
