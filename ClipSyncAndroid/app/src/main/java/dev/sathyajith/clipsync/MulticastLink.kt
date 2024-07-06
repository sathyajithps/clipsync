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
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface


private const val PORT = 7645
private val IPV4 = InetAddress.getByName("224.0.0.123");
private val IPV6 = InetAddress.getByName("ff02::123");

enum class IpType {
    IPV4,
    IPV6
}

class MulticastLink(ipType: IpType, wifiManager: WifiManager, clipboardManager: ClipboardManager) {
    private val mIpType = ipType
    private val mWifiManager = wifiManager
    private var mWifiMulticastLock: MulticastLock? = null
    private var mSenderSocket: MulticastSocket
    private var mServerHandle: Job

    init {
        getMulticastLock()

        val addr = when (ipType) {
            IpType.IPV4 -> InetSocketAddress(IPV4, PORT)
            IpType.IPV6 -> InetSocketAddress(IPV6, PORT)
        }

        mSenderSocket = MulticastSocket(PORT)
        mSenderSocket.joinGroup(addr.address)
        mSenderSocket.loopbackMode = true

        // Server creation
        mServerHandle = CoroutineScope(Dispatchers.IO).launch {
            val serverMulticastSocket = MulticastSocket(addr)
            serverMulticastSocket.loopbackMode = true

            when (ipType) {
                IpType.IPV4 -> serverMulticastSocket.joinGroup(
                    addr,
                    NetworkInterface.getByName("0.0.0.0")
                )

                IpType.IPV6 -> serverMulticastSocket.joinGroup(addr, NetworkInterface.getByIndex(0))
            }

            Log.i(CST, "Server joined multicast address: $addr")
            Log.i(CST, "Server ready")

            while (true) {
                val buffer = ByteArray(1024)
                val pkt = DatagramPacket(buffer, 1024)
                serverMulticastSocket.receive(pkt)

                val dataReceived = pkt.data.slice(0..<pkt.length).toByteArray()

                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText(
                        "ClipSync",
                        dataReceived.toString(Charsets.UTF_8)
                    )
                )
                Log.i(
                    CST,
                    "Received data from: ${pkt.address}"
                )
            }
        }
    }

    fun sendData(data: String) {
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
            mSenderSocket.send(pkt)
        }
    }

    fun dispose() {
        mServerHandle.cancel("Disposed")
        mSenderSocket.close()
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
