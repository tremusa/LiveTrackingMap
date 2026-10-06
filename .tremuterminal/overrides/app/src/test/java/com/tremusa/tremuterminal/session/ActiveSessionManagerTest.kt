package com.tremusa.tremuterminal.session

import com.tremusa.tremuterminal.connection.ConnectionRepository
import com.tremusa.tremuterminal.connection.SavedConnection
import com.tremusa.tremuterminal.security.SecretStore
import com.tremusa.tremuterminal.ssh.HostKeyPrompt
import com.tremusa.tremuterminal.ssh.InMemoryHostKeyStore
import com.tremusa.tremuterminal.ssh.SshConnectionConfig
import com.tremusa.tremuterminal.ssh.SshTransport
import com.tremusa.tremuterminal.ssh.SshTransportListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class ActiveSessionManagerTest {
    private class FakeTransport : SshTransport {
        override var isConnected = false
        var disconnectCount = 0
        var resizeCount = 0
        val writes = mutableListOf<ByteArray>()
        lateinit var listener: SshTransportListener
        var lastConfig: SshConnectionConfig? = null
        var lastPasswordText: String? = null
        override fun connect(config: SshConnectionConfig, hostKeyPrompt: HostKeyPrompt, listener: SshTransportListener) {
            this.listener = listener
            lastConfig = config
            lastPasswordText = config.password?.toString(Charsets.UTF_8)
            isConnected = true
        }
        override fun write(data: ByteArray, offset: Int, count: Int) { writes += data.copyOfRange(offset, offset + count) }
        override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) { resizeCount++ }
        override fun disconnect() { disconnectCount++; isConnected = false }
    }

    @Test fun rendererDetach_doesNotDisconnectLiveTransport() {
        val transport = FakeTransport()
        val connection = SavedConnection(name="Pi", host="pi", username="root")
        val manager = manager(connection, transport)
        manager.connect(connection.id)
        manager.attachRenderer {}
        manager.detachRenderer()
        assertTrue(transport.isConnected)
        assertEquals(0, transport.disconnectCount)
    }

    @Test fun resize_isForwardedWithoutReconnecting() {
        val transport = FakeTransport()
        val connection = SavedConnection(name="Pi", host="pi", username="root")
        val manager = manager(connection, transport)
        manager.connect(connection.id)
        manager.resize(120, 40, 1200, 800)
        assertEquals(1, transport.resizeCount)
        assertEquals(0, transport.disconnectCount)
    }

    @Test fun directConnection_usesEphemeralPasswordWithoutRepositorySave() {
        val transport = FakeTransport()
        val connection = SavedConnection(name="Temp", host="10.0.0.2", username="root")
        val manager = manager(connection, transport)
        manager.connectDirect(connection, "secret", null)
        assertTrue(transport.isConnected)
        assertEquals("secret", transport.lastPasswordText)
        assertEquals(connection.id, manager.currentConnectionId())
    }

    private fun manager(connection: SavedConnection, transport: FakeTransport): ActiveSessionManager {
        val repo = object : ConnectionRepository {
            override fun list() = listOf(connection)
            override fun get(id: String) = if (id == connection.id) connection else null
            override fun save(connection: SavedConnection) = Unit
            override fun delete(id: String) = Unit
        }
        val secrets = object : SecretStore {
            override fun put(key: String, value: String) = Unit
            override fun get(key: String): String? = "pw"
            override fun delete(key: String) = Unit
        }
        return ActiveSessionManager(
            connections = repo,
            secrets = secrets,
            hostKeys = InMemoryHostKeyStore(),
            privateKeyLoader = { null },
            transportFactory = { transport },
            executor = Executor { it.run() }
        )
    }
}
