package org.zywx.wbpalmstar.plugin.uexmultiHttp;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

import org.apache.http.HttpStatus;
import org.apache.http.cookie.SM;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.ByteArrayBuffer;
import org.json.JSONObject;
import org.zywx.wbpalmstar.platform.certificates.Http;
import org.zywx.wbpalmstar.widgetone.dataservice.WWidgetData;

import android.os.Build;
import android.os.Process;
import android.util.Log;

public class EHttpGet extends Thread implements HttpTask {

	static final int F_SHEM_ID_HTTP = 0;
	static final int F_SHEM_ID_HTTPS = 1;

	static final int BODY_TYPE_TEXT = 0;
	static final int BODY_TYPE_FILE = 1;

	public static final String UA = "Mozilla/5.0 (Linux; U; Mobile; "
			+ "Android " + Build.VERSION.RELEASE + ";" + Build.MODEL
			+ " Build/FRF91 )";

	private URL mClient;
	private HttpURLConnection mConnection;
	private InputStream mInStream;
	private int mShemeId;
	private String mUrl;
	private String mRedirects;
	private boolean mFromRedirects;
	private int mTimeOut;
	private boolean mRunning;
	private boolean mCancelled;
	private int mXmlHttpID;
	private String mCertPassword;
	private String mCertPath;
	private boolean mHasLocalCert;
	private EUExXmlHttpMgr mXmlHttpMgr;
	private Hashtable<String, String> mHttpHead;

	// private String mBody;

	public EHttpGet(String inXmlHttpID, String url, int timeout,
			EUExXmlHttpMgr meUExXmlHttpMgr) {
		setName("SoTowerMobile-HttpGet");
		mXmlHttpID = Integer.parseInt(inXmlHttpID);
		mTimeOut = timeout;
		mXmlHttpMgr = meUExXmlHttpMgr;
		mUrl = url;
		mShemeId = url.startsWith("https") ? F_SHEM_ID_HTTPS : F_SHEM_ID_HTTP;
		initNecessaryHeader();
	}

	@Override
	public void setPostData(int inDataType, String inKey, String inValue) {
		;
	}

	@Override
	public void setCertificate(String cPassWord, String cPath) {
		mHasLocalCert = true;
		mCertPassword = cPassWord;
		mCertPath = cPath;
	}

	@Override
	public void send() {
		if (mRunning || mCancelled) {
			return;
		}
		mRunning = true;
		start();
	}

	@Override
	public void run() {
		if (mCancelled) {
			return;
		}
		if (Build.VERSION.SDK_INT < 8) {
			System.setProperty("http.keepAlive", "false");
		}
		Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
		doInBackground();
	}

	private void doInBackground() {
		if (mCancelled) {
			return;
		}
		String result = "";
		String curUrl;
		if (null == mUrl) {
			return;
		}
		if (mFromRedirects && null != mRedirects) {
			// 若为请求地址为重定向地址，则需要判断地址类型从而保证地址为可访问的绝对地址
			if (mRedirects.startsWith("http") || mRedirects.startsWith("https")) {
				curUrl = mRedirects;
			} else if (mRedirects.startsWith("/")) {
				// 返回地址为相对地址，需要处理
				if (mClient != null) {
					curUrl = mClient.getProtocol() + "://" + mClient.getHost()
							+ mRedirects;
				} else {
					// 为了防止某些特殊情况mClient为null
					curUrl = mRedirects;
				}
			} else {
				curUrl = mRedirects;
			}
		} else {
			curUrl = mUrl;
		}
		boolean https = false;
		try {
			mClient = new URL(curUrl);
			switch (mShemeId) {
			case F_SHEM_ID_HTTP:
				mConnection = (HttpURLConnection) mClient.openConnection();
				break;
			case F_SHEM_ID_HTTPS:
				mConnection = (HttpsURLConnection) mClient.openConnection();
				javax.net.ssl.SSLSocketFactory ssFact = null;
				if (mHasLocalCert) {
					ssFact = Http.getSSLSocketFactoryWithCert(mCertPassword,
							mCertPath, mXmlHttpMgr.getContext());
				} else {
					ssFact = new HNetSSLSocketFactory(null, null);
				}
				((HttpsURLConnection) mConnection).setSSLSocketFactory(ssFact);
				((HttpsURLConnection) mConnection)
						.setHostnameVerifier(new HX509HostnameVerifier());
				https = true;
				break;
			}
			mConnection.setRequestMethod("GET");
			mConnection.setRequestProperty("Accept-Encoding", "gzip, deflate");
			String cookie = null;
			cookie = mXmlHttpMgr.getCookie(curUrl);
			if (null != cookie) {
				mConnection.setRequestProperty(SM.COOKIE, cookie);
			}
			mConnection.setUseCaches(false);
			addHeaders();
			mConnection.setReadTimeout(mTimeOut);
			mConnection.setConnectTimeout(mTimeOut);
			mConnection.setInstanceFollowRedirects(false);
			mXmlHttpMgr.printHeader(-1, mXmlHttpID, curUrl, true,
					mConnection.getRequestProperties());
			mConnection.connect();
			int responseCode = mConnection.getResponseCode();
			Map<String, List<String>> headers = mConnection.getHeaderFields();
			mXmlHttpMgr.printHeader(responseCode, mXmlHttpID, curUrl, false,
					headers);
			switch (responseCode) {
			case HttpStatus.SC_OK:
				byte[] bResult = toByteArray(mConnection);
				result = new String(bResult, HTTP.UTF_8);
				break;
			case HttpStatus.SC_MOVED_PERMANENTLY:
			case HttpStatus.SC_MOVED_TEMPORARILY:
			case HttpStatus.SC_TEMPORARY_REDIRECT:
				List<String> urls = headers.get("Location");
				if (null != urls && urls.size() > 0) {
					Log.i("xmlHttpMgr", "redirect url " + responseCode);
					mRedirects = urls.get(0);
					mFromRedirects = true;
					handleCookie(curUrl, headers);
					doInBackground();
					return;
				}
				break;
			case HttpStatus.SC_UNAUTHORIZED:
				result = "error:unauthorized";
				break;
			default:
				result = "error:" + responseCode;
				break;
			}
			handleCookie(curUrl, headers);
		} catch (Exception e) {
			e.printStackTrace();
			if ((e instanceof IOException) && https) {
				result = "error:unauthorized";
			} else {
				result = "error:net work error or timeout!";
			}
		} finally {
			if (null != mInStream) {
				try {
					mInStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (null != mConnection) {
				mConnection.disconnect();
			}
		}
		if (mCancelled) {
			return;
		}
		mXmlHttpMgr.printResult(mXmlHttpID, curUrl, result);
		mXmlHttpMgr.onFinish(mXmlHttpID);
		if (result.startsWith("error")) {
			mXmlHttpMgr.errorCallBack(mXmlHttpID, result);
			return;
		}
		mXmlHttpMgr.callBack(mXmlHttpID, result);
		return;
	}

	private void handleCookie(String url, Map<String, List<String>> headers) {
		if (null == headers) {
			return;
		}
		List<String> setCookies = headers.get(SM.SET_COOKIE);
		if (null != setCookies) {
			for (String v : setCookies) {
				mXmlHttpMgr.setCookie(url, v);
			}
		} else {
			setCookies = headers.get("set-cookie");
			if (null != setCookies) {
				for (String v : setCookies) {
					mXmlHttpMgr.setCookie(url, v);
				}
			}
		}
		List<String> Cookie = headers.get(SM.COOKIE);
		if (null != Cookie) {
			for (String v : Cookie) {
				mXmlHttpMgr.setCookie(url, v);
			}
		} else {
			Cookie = headers.get("cookie");
			if (null != Cookie) {
				for (String v : Cookie) {
					mXmlHttpMgr.setCookie(url, v);
				}
			}
		}
		List<String> Cookie2 = headers.get(SM.COOKIE2);
		if (null != Cookie2) {
			for (String v : Cookie2) {
				mXmlHttpMgr.setCookie(url, v);
			}
		} else {
			Cookie2 = headers.get("cookie2");
			if (null != Cookie2) {
				for (String v : Cookie2) {
					mXmlHttpMgr.setCookie(url, v);
				}
			}
		}
	}

	@Override
	public void cancel() {
		mCancelled = true;
		try {
			if (null != mInStream) {
				mInStream.close();
			}
			if (null != mConnection) {
				mConnection.disconnect();
			}
			interrupt();
		} catch (Exception e) {
			e.printStackTrace();
		}
		mTimeOut = 0;
		mUrl = null;
		mRunning = false;
		mConnection = null;
		mClient = null;
	}

	private byte[] toByteArray(HttpURLConnection conn) throws Exception {
		if (null == conn) {
			return new byte[] {};
		}
		mInStream = conn.getInputStream();
		if (mInStream == null) {
			return new byte[] {};
		}
		long len = conn.getContentLength();
		if (len > Integer.MAX_VALUE) {
			throw new Exception(
					"HTTP entity too large to be buffered in memory");
		}
		String contentEncoding = conn.getContentEncoding();
		boolean gzip = false;
		if (null != contentEncoding) {
			if ("gzip".equalsIgnoreCase(contentEncoding)) {
				mInStream = new GZIPInputStream(mInStream, 2048);
				gzip = true;
			}
		}
		ByteArrayBuffer buffer = new ByteArrayBuffer(1024 * 8);
		// \&:38, \n:10, \r:13, \':39, \":34, \\:92
		try {
			if (gzip) {
				int lenth = 0;
				while (lenth != -1) {
					byte[] buf = new byte[2048];
					try {
						lenth = mInStream.read(buf, 0, buf.length);
						if (lenth != -1) {
							buffer.append(buf, 0, lenth);
						}
					} catch (EOFException e) {
						int tl = buf.length;
						int surpl;
						for (int k = 0; k < tl; ++k) {
							surpl = buf[k];
							if (surpl != 0) {
								buffer.append(surpl);
							}
						}
						lenth = -1;
					}
				}
				int bl = buffer.length();
				ByteArrayBuffer temBuffer = new ByteArrayBuffer(
						(int) (bl * 1.4));
				for (int j = 0; j < bl; ++j) {
					int cc = buffer.byteAt(j);
					if (cc == 34 || cc == 39 || cc == 92 || cc == 10
							|| cc == 13 || cc == 38) {
						temBuffer.append('\\');
					}
					temBuffer.append(cc);
				}
				buffer = temBuffer;
			} else {
				int c;
				while ((c = mInStream.read()) != -1) {
					if (c == 34 || c == 39 || c == 92 || c == 10 || c == 13
							|| c == 38) {
						buffer.append('\\');
					}
					buffer.append(c);
				}
			}
		} catch (Exception e) {
			mInStream.close();
		} finally {
			mInStream.close();
		}
		return buffer.toByteArray();
	}

	@Override
	public void setHeaders(String headJson) {
		try {
			JSONObject json = new JSONObject(headJson);
			Iterator<?> keys = json.keys();
			while (keys.hasNext()) {
				String key = (String) keys.next();
				String value = json.getString(key);
				mHttpHead.put(key, value);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void setInputStream(File file) {
		;
	}

	@Override
	public void setBody(String body) {
		// mBody = body;
	}

	private void addHeaders() {
		if (null != mConnection) {
			Set<Entry<String, String>> entrys = mHttpHead.entrySet();
			for (Map.Entry<String, String> entry : entrys) {

				mConnection
						.setRequestProperty(entry.getKey(), entry.getValue());
			}
		}
	}

	private void initNecessaryHeader() {
		mHttpHead = new Hashtable<String, String>();
		mHttpHead.put("Accept", "*/*");
		mHttpHead.put("Charset", HTTP.UTF_8);
		mHttpHead.put("User-Agent", UA);
		mHttpHead.put("Connection", "Keep-Alive");
		mHttpHead.put("Accept-Encoding", "gzip, deflate");
	}

	@Override
	public void setAppVerifyHeader(WWidgetData curWData) {
		mHttpHead.put(
				XmlHttpUtil.KEY_APPVERIFY,
				XmlHttpUtil.getAppVerifyValue(curWData,
						System.currentTimeMillis()));
	}
}
