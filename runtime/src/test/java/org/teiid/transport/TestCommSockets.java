/*
 * JBoss, Home of Professional Open Source.
 * Copyright (C) 2008 Red Hat, Inc.
 * Licensed to Red Hat, Inc. under one or more contributor 
 * license agreements.  See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.teiid.client.security.ILogon;
import org.teiid.common.buffer.BufferManagerFactory;
import org.teiid.common.buffer.impl.MemoryStorageManager;
import org.teiid.core.crypto.NullCryptor;
import org.teiid.core.util.ObjectConverterUtil;
import org.teiid.core.util.UnitTestUtil;
import org.teiid.dqp.service.SessionServiceException;
import org.teiid.net.CommunicationException;
import org.teiid.net.ConnectionException;
import org.teiid.net.ServerConnection;
import org.teiid.net.TeiidURL;
import org.teiid.net.socket.SocketServerConnection;
import org.teiid.net.socket.SocketServerConnectionFactory;
import org.teiid.net.socket.SocketUtil;
import org.teiid.net.socket.UrlServerDiscovery;
import org.teiid.services.SessionServiceImpl;
import org.teiid.transport.TestSocketRemoting.FakeService;


@SuppressWarnings("nls")
public class TestCommSockets {

	SocketListener listener;
	private SocketServerConnectionFactory sscf;
	private InetSocketAddress addr;
	private MemoryStorageManager storageManager;
	private SessionServiceImpl service;

	@Before public void setUp() {
		addr = new InetSocketAddress(0);
	}
	
	@After public void tearDown() throws Exception {
		if (listener != null) {
			listener.stop();
		}
	}

	@Test(expected=CommunicationException.class) public void testFailedConnect() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		listener = new SocketListener(addr,1024, 1024, 1, config, null, BufferManagerFactory.getStandaloneBufferManager());

		Properties p = new Properties();
		String url = new TeiidURL(addr.getHostName(), listener.getPort() - 1, false).getAppServerURL();
		p.setProperty(TeiidURL.CONNECTION.SERVER_URL, url); //wrong port
		SocketServerConnectionFactory.getInstance().getConnection(p);
	}

	@Test public void testConnectWithoutPooling() throws Exception {
		Properties p = new Properties();
		p.setProperty("org.teiid.sockets.maxCachedInstances", String.valueOf(0)); //$NON-NLS-1$
		SocketServerConnection conn = helpEstablishConnection(false, new SSLConfiguration(), p);
		SocketListenerStats stats = listener.getStats();
		assertEquals(2, stats.objectsRead); // handshake response, logon,
		assertEquals(1, stats.sockets);
		conn.close();
		stats = listener.getStats();
		assertEquals(1, stats.maxSockets);
		assertEquals(3, stats.objectsRead); // handshake response, logon, logoff
		if (stats.sockets > 0) {
			// there is a timing issue here, since the effect of shutdown on the
			// server side can be delayed
			Thread.sleep(500);
		}
		stats = listener.getStats();
		assertEquals(0, stats.sockets);
	}
	
	@Test public void testConnectWithPooling() throws Exception {
		SocketServerConnection conn = helpEstablishConnection(false);
		SocketListenerStats stats = listener.getStats();
		assertEquals(2, stats.objectsRead); // handshake response, logon
		assertEquals(1, stats.sockets);
		conn.close();
		stats = listener.getStats();
		assertEquals(1, stats.maxSockets);
		assertEquals(3, stats.objectsRead); // handshake response, logon, logoff
		stats = listener.getStats();
		assertEquals(1, stats.sockets);
		conn = helpEstablishConnection(false);
		conn.close();
		stats = listener.getStats();
		assertEquals(1, stats.sockets);
		assertEquals(1, stats.maxSockets);
	}

	@Test public void testLobs() throws Exception {
		SocketServerConnection conn = helpEstablishConnection(false);
		FakeService fs = conn.getService(FakeService.class);
		assertEquals(150, fs.lobMethod(new ByteArrayInputStream(new byte[100]), new StringReader(new String(new char[50]))));
		assertEquals(2, storageManager.getCreated());
		assertEquals(2, storageManager.getRemoved());
		assertEquals(0, fs.lobMethod(new ByteArrayInputStream(new byte[0]), new StringReader(new String(new char[0]))));
		assertEquals(4, storageManager.getCreated());
		assertEquals(4, storageManager.getRemoved());
		assertEquals((1 << 17) + 50, fs.lobMethod(new ByteArrayInputStream(new byte[1 << 17]), new StringReader(new String(new char[50]))));
		assertEquals(6, storageManager.getCreated());
		assertEquals(6, storageManager.getRemoved());
	}
	
	@Test public void testServerRemoteStreaming() throws Exception {
		SocketServerConnection conn = helpEstablishConnection(false);
		FakeService fs = conn.getService(FakeService.class);
		assertEquals("hello world", ObjectConverterUtil.convertToString(fs.getReader()));
		assertTrue(Serializable.class.isAssignableFrom(fs.getReader().getClass()));
	}

	@Test public void testConnectWithoutClientEncryption() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode(SSLConfiguration.DISABLED);
		SocketServerConnection conn = helpEstablishConnection(false, config, new Properties());
		assertTrue(conn.selectServerInstance(false).getCryptor() instanceof NullCryptor);
		conn.close();
	}

	private SocketServerConnection helpEstablishConnection(boolean secure) throws CommunicationException, ConnectionException {
		return helpEstablishConnection(secure, new SSLConfiguration(), new Properties());
	}

	private SocketServerConnection helpEstablishConnection(boolean clientSecure, SSLConfiguration config, Properties socketConfig) throws CommunicationException,
			ConnectionException {
		if (listener == null) {
			ClientServiceRegistryImpl server = new ClientServiceRegistryImpl() {
				@Override
				public ClassLoader getCallerClassloader() {
					return getClass().getClassLoader();
				}
			};
			service = new SessionServiceImpl();
			server.registerClientService(ILogon.class, new LogonImpl(service, "fakeCluster"), null); 
			server.registerClientService(FakeService.class, new TestSocketRemoting.FakeServiceImpl(), null);
			storageManager = new MemoryStorageManager();
			listener = new SocketListener(addr, 1024, 1024, 1, config, server, storageManager);
			
			SocketListenerStats stats = listener.getStats();
			assertEquals(0, stats.maxSockets);
			assertEquals(0, stats.objectsRead);
			assertEquals(0, stats.objectsWritten);
			assertEquals(0, stats.sockets);
		}

		Properties p = new Properties();
		String url = new TeiidURL(addr.getHostName(), listener.getPort(), clientSecure).getAppServerURL();
		p.setProperty(TeiidURL.CONNECTION.SERVER_URL, url); 
		p.setProperty(TeiidURL.CONNECTION.APP_NAME, "test");
		p.setProperty(TeiidURL.CONNECTION.DISCOVERY_STRATEGY, UrlServerDiscovery.class.getName());
		if (sscf == null) {
			sscf = new SocketServerConnectionFactory();
			sscf.initialize(socketConfig);
		}
		return sscf.getConnection(p);
	}

	@Test public void testSSLConnectWithNonSSLServer() throws Exception {
		//first make a non-ssl connection to ensure that it's not reused
		SocketServerConnection conn = helpEstablishConnection(false);
		conn.close();
		try {
			helpEstablishConnection(true);
			fail("exception expected"); //$NON-NLS-1$
		} catch (CommunicationException e) {
			
		}
	}

	@Test public void testAnonSSLConnect() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode(SSLConfiguration.ENABLED);
		config.setEnabledCipherSuites("x"); //ensure that this cipher suite is not used
		config.setAuthenticationMode(SSLConfiguration.ANONYMOUS);
		Properties p = new Properties();
		helpEstablishConnection(true, config, p);
		SocketServerConnection conn = helpEstablishConnection(true, config, p);
		conn.close();
		
		try {
			helpEstablishConnection(false, config, p);
			fail();
		} catch (CommunicationException e) {
			
		}
	}
	
	@Test(expected=CommunicationException.class) public void testNonAnonSSLConnectWithSSLServer() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode(SSLConfiguration.ENABLED);
		config.setAuthenticationMode(SSLConfiguration.ANONYMOUS);
		Properties p = new Properties();
		p.setProperty(SocketUtil.ALLOW_ANON, Boolean.FALSE.toString());
		helpEstablishConnection(true, config, p);
	}
	
	@Test(expected=CommunicationException.class) public void testOnewayFails() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode(SSLConfiguration.ENABLED);
		config.setAuthenticationMode(SSLConfiguration.ONEWAY);
		Properties p = new Properties();
		helpEstablishConnection(true, config, p);
		//TODO: we may want to clarify the server exception in this case, which
		//is just that there are no cipher suites in common
	}
	
	/**
	 * shows one-way auth with a key alias/password
	 */
	@Test public void testSSLSelfSigned() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode(SSLConfiguration.ENABLED);
		config.setAuthenticationMode(SSLConfiguration.ONEWAY);
		config.setKeystoreFilename(UnitTestUtil.getTestDataPath() + "/keystore.jks");
		config.setKeystorePassword("password");
		config.setKeystoreKeyPassword("changeit");
		config.setKeystoreKeyAlias("selfsigned");
		Properties p = new Properties();
		p.setProperty("org.teiid.ssl.trustStore", UnitTestUtil.getTestDataPath() + "/keystore.jks");
		p.setProperty("org.teiid.ssl.trustStorePassword", "password");
		helpEstablishConnection(true, config, p);
	}
	
	@Test public void testSSLSelfSignedTrustAll() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode(SSLConfiguration.ENABLED);
		config.setAuthenticationMode(SSLConfiguration.ONEWAY);
		config.setKeystoreFilename(UnitTestUtil.getTestDataPath() + "/keystore.jks");
		config.setKeystorePassword("password");
		config.setKeystoreKeyPassword("changeit");
		config.setKeystoreKeyAlias("selfsigned");
		Properties p = new Properties();
		p.setProperty("org.teiid.ssl.trustAll", "true");
		helpEstablishConnection(true, config, p);
	}
	
	@Test public void testTwoWaySSLSelfSigned() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode(SSLConfiguration.ENABLED);
		config.setAuthenticationMode(SSLConfiguration.TWOWAY);
		config.setKeystoreFilename(UnitTestUtil.getTestDataPath() + "/keystore.jks");
		config.setKeystorePassword("password");
		config.setKeystoreKeyPassword("changeit");
		config.setKeystoreKeyAlias("selfsigned");
		config.setTruststoreFilename(UnitTestUtil.getTestDataPath() + "/keystore.jks");
		config.setTruststorePassword("password");
		Properties p = new Properties();
		p.setProperty("org.teiid.ssl.trustStore", UnitTestUtil.getTestDataPath() + "/keystore.jks");
		p.setProperty("org.teiid.ssl.trustStorePassword", "password");
		p.setProperty("org.teiid.ssl.keyStore", UnitTestUtil.getTestDataPath() + "/keystore.jks");
		p.setProperty("org.teiid.ssl.keyStorePassword", "password");
		p.setProperty("org.teiid.ssl.keyPassword", "changeit");
		helpEstablishConnection(true, config, p);
	}
	
	@Test public void testSelectNewInstanceWithoutPooling() throws Exception {
		Properties p = new Properties();
		p.setProperty("org.teiid.sockets.maxCachedInstances", "0");
		helpTestNewInstance(p);
	}
	
	@Test public void testSelectNewInstanceWithPooling() throws Exception {
		Properties p = new Properties();
		p.setProperty("org.teiid.sockets.maxCachedInstances", "16");
		helpTestNewInstance(p);
	}

	private void helpTestNewInstance(Properties p)
			throws CommunicationException, ConnectionException,
			SessionServiceException {
		SSLConfiguration config = new SSLConfiguration();
		SocketServerConnection conn = helpEstablishConnection(false, config, p);
		SocketListenerStats stats = listener.getStats();
		assertEquals(2, stats.objectsRead); // handshake response, logon,
		assertEquals(1, stats.sockets);
		conn.cleanUp();
		assertEquals(1, this.service.getActiveSessionsCount());
		ServerConnection conn2 = helpEstablishConnection(false, config, p);
		assertEquals(2, this.service.getActiveSessionsCount());
		conn.selectServerInstance(false);
		assertEquals(2, this.service.getActiveSessionsCount());
		assertTrue(conn.isOpen(1000));
		stats = listener.getStats();
		assertEquals(8, stats.objectsRead); // (ping (pool test), assert identityx2, ping (isOpen))x2
		assertEquals(2, stats.sockets);
		conn.close();
		conn2.close();
	}
	
	@Test public void testEnableCipherSuites() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setEnabledCipherSuites("x,y,z");
		assertArrayEquals(new String[] {"x","y","z"}, config.getEnabledCipherSuitesAsArray());
	}
	
	@Test public void testAnonSSLMode() throws Exception {
		SSLConfiguration config = new SSLConfiguration();
		config.setMode("enabled");
		assertFalse(config.isClientEncryptionEnabled());
		assertTrue(config.isSslEnabled());
		config.setMode("login");
		assertTrue(config.isClientEncryptionEnabled());
	}
	
}
