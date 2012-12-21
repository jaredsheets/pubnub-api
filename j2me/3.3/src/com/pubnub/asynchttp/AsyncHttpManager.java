package com.pubnub.asynchttp;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

public class AsyncHttpManager {

	private static int _maxWorkers = 1;
	private Vector _waiting = new Vector();
	private Worker _workers[];
	private static Network network;

	private class Network {
		private boolean available = true;

		public boolean isAvailable() {
			return available;
		}
		public void available() {
			available = true;
		}
		public void unavailable() {
			available = false;
		}
	}
	
	public static void startHeartbeat(String url, int interval) {
		new Thread(new Heartbeat(url, interval), "heartbeat").start();
	}

	public void cancel(HttpCallback cb) {
		for (int i = 0; i < _workers.length; ++i) {
			if (_workers[i].asyncConnection != null) {
				if (!_workers[i].getDie()) {

					cancel(_workers[i].asyncConnection);
					_workers[i].asyncConnection = null;

				}
			}
		}

	}

	private void cancel(AsyncConnection conn) {
		AsyncHttpCallback cb = conn.getCallback();

		try {

			close(conn);
			cb.cancelingCall(conn.getHttpConnection());
		} catch (IOException ignore) {
		} finally {
			close(conn);

		}
	}

	public void cancelAll() {
		synchronized (_waiting) {

			for (int i = 0; i < _workers.length; ++i) {
				_workers[i].die();
			}

			while (_waiting.size() != 0) {
				AsyncConnection conn = (AsyncConnection) _waiting
						.firstElement();
				_waiting.removeElementAt(0);

				cancel(conn);
			}
			_workers = null;
			_waiting.notifyAll();

		}
	}

	private void close(AsyncConnection conn) {
		if (conn != null) {
			close(conn.getHttpConnection());
			conn.setHttpConnection(null);
		}
	}

	private static void close(HttpConnection hc) {
		if (hc != null) {
			try {
				hc.close();
			} catch (IOException e) {

			}
		}
	}

	public static int getWorkerCount() {
		return _maxWorkers;
	}

	private void init(int maxCalls, String name) {
		if (maxCalls < 1) {
			maxCalls = 1;
		}
		_workers = new Worker[maxCalls];
		for (int i = 0; i < maxCalls; ++i) {
			System.out.println("Max workers : " + maxCalls);
			Worker w = new Worker();
			_workers[i] = w;
			new Thread(w, name).start();
		}
		if (network == null) {
			network = new Network();
		}
	}

	public AsyncHttpManager(String name) {
		init(_maxWorkers, name);
	}

	public static boolean isRedirect(int rc) {
		return (rc == HttpConnection.HTTP_MOVED_PERM
				|| rc == HttpConnection.HTTP_MOVED_TEMP
				|| rc == HttpConnection.HTTP_SEE_OTHER || rc == HttpConnection.HTTP_TEMP_REDIRECT);
	}

	public void queue(AsyncHttpCallback request) {
		queue(request, null);
	}

	public void queue(AsyncHttpCallback cb, HttpConnection hc) {

		if (!network.isAvailable()) {
			try {
				cb.errorCall(hc, 0, "[0,'Network Error']");
			} catch (IOException e) {
			}
			return;
		}
		cb.setConnManager(this);
		synchronized (_waiting) {
			AsyncConnection conn = new AsyncConnection(cb, hc);
			_waiting.addElement(conn);
			_waiting.notifyAll();
		}
	}

	public static void setWorkerCount(int count) {
		_maxWorkers = count;
	}

	private static class AsyncConnection {

		AsyncConnection(AsyncHttpCallback cb, HttpConnection hc) {
			_callback = cb;
			_httpconn = hc;
		}

		AsyncHttpCallback getCallback() {
			return _callback;
		}

		HttpConnection getHttpConnection() {
			return _httpconn;
		}

		void setHttpConnection(HttpConnection hc) {
			_httpconn = hc;
		}

		private AsyncHttpCallback _callback;
		private HttpConnection _httpconn;
	}

	private static class Heartbeat implements Runnable {
		private boolean runHeartbeat = true;
		private String heartbeatUrl;
		private int heartbeatInterval;
		public Heartbeat(String url, int interval) {
			this.heartbeatUrl = url;
			this.heartbeatInterval = interval;
		}
		public void run() {
			while(runHeartbeat) {
				HttpConnection hc = null;
				try {

					hc = (HttpConnection) Connector.open(
							heartbeatUrl,
							Connector.READ_WRITE, true);
					hc.setRequestMethod(HttpConnection.GET);

					int rc =  hc.getResponseCode();
					System.out.println("HEARTBEAT : " + rc);
					if (rc == HttpConnection.HTTP_OK) {
						network.available();
						synchronized(network) {
							network.notifyAll();
						}
					} else
						network.unavailable();

					close(hc);

				} catch (IOException e) {
					network.unavailable();
				} finally {
					if (hc != null)
						close(hc);
				}
				try {
					Thread.sleep(heartbeatInterval);
				} catch (InterruptedException e) {

				}
			}
		}	

	}

	private class Worker implements Runnable {

		public void die() {
			_die = true;
		}

		public boolean getDie() {
			return _die;
		}

		private void process(AsyncConnection conn) {

			AsyncHttpCallback cb = conn.getCallback();
			String url = null;
			HttpConnection hc = null;
			try {
				hc = conn.getHttpConnection();

				boolean process = true;

				if (hc == null) {
					url = cb.startingCall();
					if (url == null) {
						cancel(conn);
						return;
					}
				}

				int follow = 5;

				while (follow-- > 0) {
					hc = conn.getHttpConnection();

					if (hc == null) {

						try {
							System.out.println(url);
							hc = (HttpConnection) Connector.open(url,
									Connector.READ_WRITE, true);
							hc.setRequestMethod(HttpConnection.GET);
							Hashtable headers = cb.getHeaderFields();
							Enumeration en = headers.keys();
							while (en.hasMoreElements()) {
								String key = (String) en.nextElement();
								String val = (String) headers.get(key);
								hc.setRequestProperty(key, val);

							}

							conn.setHttpConnection(hc);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
					cb.setConnection(hc);
					if (!cb.prepareRequest(hc)) {
						cancel(conn);
						return;
					}
					int rc = 0;
					hc.getResponseCode();
					if (!cb.checkResponse(hc)) {
						process = false;
						break;
					} else if (!isRedirect(rc)) {
						break;
					}

					url = hc.getHeaderField("Location");
					if (url == null) {
						throw new IOException("No Location header");
					}

					if (url.startsWith("/")) {
						StringBuffer b = new StringBuffer();
						b.append("http://");
						b.append(hc.getHost());
						b.append(':');
						b.append(hc.getPort());
						b.append(url);
						url = b.toString();
					} else if (url.startsWith("ttp:")) {
						url = "h" + url;
					}

					conn.setHttpConnection(null);
					close(hc);
				}

				if (follow == 0) {
					throw new IOException("Too many redirects");
				}

				if (process) {
					cb.processResponse(hc);
				}

				cb.endingCall(hc);
				asyncConnection = null;
				close(conn);
			} catch (Exception e) {
				System.out.println(e.toString());
				try {
					cb.errorCall(hc,0,e.toString());
				} catch (IOException e1) {
				}
			} finally {
				close(conn);
			}

		}

		public void run() {
			do {
				AsyncConnection conn = null;

				synchronized (_waiting) {

					while (!_die) {

						if (!network.isAvailable()) {
							System.out.println("Network not available. Lets wait " + Thread.currentThread().getName());
							synchronized(network) {
								try {
									network.wait();
								} catch (InterruptedException e1) {

								}
							}
						}
						if (_waiting.size() != 0) {
							conn = (AsyncConnection) _waiting.firstElement();
							_waiting.removeElementAt(0);
							break;
						}

						try {
							_waiting.wait(1000);
						} catch (InterruptedException e) {
						}
					}
				}

				if (conn != null) {
					asyncConnection = conn;
					if (_die) {
						cancel(conn);
					} else {
						process(conn);
					}
				}
			} while (!_die);
			System.out.println("EXITING WORKER");
		}

		public volatile boolean _die;
		public AsyncConnection asyncConnection;
	}
}