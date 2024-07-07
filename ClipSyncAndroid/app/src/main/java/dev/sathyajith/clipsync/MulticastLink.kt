package dev.sathyajith.clipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException


private const val PORT = 7645
private val IPV4 = InetAddress.getByName("224.0.0.123")
private val IPV6 = InetAddress.getByName("ff02::123")

enum class IpType {
    IPV4,
    IPV6
}

class MulticastLink(
    private val mIpType: IpType,
    private val mWifiManager: WifiManager,
    private val mClipboardManager: ClipboardManager
) {
    private var mWifiMulticastLock: MulticastLock? = null
    private var mSenderSocket: MulticastSocket? = null
    private var mServerHandle: Job? = null

    init {
        create()
    }

    fun create() {
        getMulticastLock()

        val addr = when (mIpType) {
            IpType.IPV4 -> InetSocketAddress(IPV4, PORT)
            IpType.IPV6 -> InetSocketAddress(IPV6, PORT)
        }

        mSenderSocket = MulticastSocket(PORT)
        mSenderSocket!!.joinGroup(addr.address)
        mSenderSocket!!.loopbackMode = true

        // Server creation
        mServerHandle = CoroutineScope(Dispatchers.IO).launch {
            val serverMulticastSocket = MulticastSocket(addr)
            serverMulticastSocket.loopbackMode = true

            when (mIpType) {
                IpType.IPV4 -> serverMulticastSocket.joinGroup(
                    addr,
                    NetworkInterface.getByName("0.0.0.0")
                )

                IpType.IPV6 -> serverMulticastSocket.joinGroup(addr, NetworkInterface.getByIndex(0))
            }

            Log.i(CST, "Server joined multicast address: $addr")
            Log.i(CST, "Server ready")

            while (true) {
                if (!isActive) {
                    when (mIpType) {
                        IpType.IPV4 -> serverMulticastSocket.leaveGroup(
                            addr,
                            NetworkInterface.getByName("0.0.0.0")
                        )

                        IpType.IPV6 -> serverMulticastSocket.leaveGroup(
                            addr,
                            NetworkInterface.getByIndex(0)
                        )
                    }
                }
                val buffer = ByteArray(1024)
                val pkt = DatagramPacket(buffer, 1024)
                try {
                    serverMulticastSocket.soTimeout = 100
                    serverMulticastSocket.receive(pkt)

                    val dataReceived = pkt.data.slice(0..<pkt.length).toByteArray()

                    mClipboardManager.setPrimaryClip(
                        ClipData.newPlainText(
                            "ClipSync",
                            dataReceived.toString(Charsets.UTF_8)
                        )
                    )
                    Log.i(
                        CST,
                        "Received data from: ${pkt.address}"
                    )
                } catch (_: SocketTimeoutException){
                    // ignore
                }
            }
        }
    }

    fun sendData(data: String) {
        if (mSenderSocket == null) {
            throw Exception("Sender socket not initialized")
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ip = when (mIpType) {
                IpType.IPV4 -> IPV4
                IpType.IPV6 -> IPV6
            }

            val buf = data.toByteArray(Charsets.UTF_8)

            val pkt = DatagramPacket(
                buf, buf.size,
                ip, PORT
            )
            mSenderSocket!!.send(pkt)
        }
    }

    fun dispose() {
        mServerHandle?.cancel("Disposed")
        mSenderSocket?.close()
        mWifiMulticastLock?.release()
    }

    private fun getMulticastLock() {
        if (mWifiMulticastLock?.isHeld == true) {
            return
        }

        mWifiMulticastLock = null

        val lock = mWifiManager.createMulticastLock("Communication")
        lock.setReferenceCounted(false)
        lock.acquire()

        mWifiMulticastLock = lock
    }
}
