package lee.study.proxyee.server;

import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lee.study.proxyee.crt.CertUtil;
import lee.study.proxyee.exception.HttpProxyExceptionHandle;
import lee.study.proxyee.handler.HttpProxyServerHandle;
import lee.study.proxyee.intercept.CertDownIntercept;
import lee.study.proxyee.intercept.HttpProxyIntercept;
import lee.study.proxyee.intercept.HttpProxyInterceptInitializer;
import lee.study.proxyee.intercept.HttpProxyInterceptPipeline;
import lee.study.proxyee.proxy.Log4j2LoggerFactory;
import lee.study.proxyee.proxy.ProxyConfig;
import lee.study.proxyee.proxy.ProxyType;

public class HttpProxyServer {

	// http代理隧道握手成功
	public final static HttpResponseStatus SUCCESS = new HttpResponseStatus(200, "Connection established");

	private HttpProxyServerConfig serverConfig;
	private HttpProxyInterceptInitializer proxyInterceptInitializer;
	private HttpProxyExceptionHandle httpProxyExceptionHandle;
	private ProxyConfig proxyConfig;

	public HttpProxyServer() {
		try {
			init();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public SslContext getClientSslContext() {
		return serverConfig.getClientSslCtx();
	}

	private void init() throws Exception {
		// 注册BouncyCastleProvider加密库
		Security.addProvider(new BouncyCastleProvider());
		if (serverConfig == null) {
			serverConfig = new HttpProxyServerConfig();
			serverConfig.setClientSslCtx(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build());
			ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			X509Certificate certificate = CertUtil.loadCert(classLoader.getResourceAsStream("ca.crt"));
			// 读取CA证书使用者信息
			serverConfig.setIssuer(CertUtil.getSubject(certificate));
			// 读取CA证书有效时段(server证书有效期超出CA证书的，在手机上会提示证书不安全)
			serverConfig.setCaNotBefore(certificate.getNotBefore());
			serverConfig.setCaNotAfter(certificate.getNotAfter());
			// CA私钥用于给动态生成的网站SSL证书签证
			serverConfig.setCaPriKey(CertUtil.loadPriKey(classLoader.getResourceAsStream("ca_private.der")));
			// 生产一对随机公私钥用于网站SSL证书动态创建
			KeyPair keyPair = CertUtil.genKeyPair();
			serverConfig.setServerPriKey(keyPair.getPrivate());
			serverConfig.setServerPubKey(keyPair.getPublic());
			serverConfig.setLoopGroup(new NioEventLoopGroup());
		}
		if (proxyInterceptInitializer == null) {
			proxyInterceptInitializer = new HttpProxyInterceptInitializer();
		}
		if (httpProxyExceptionHandle == null) {
			httpProxyExceptionHandle = new HttpProxyExceptionHandle();
		}
	}

	public HttpProxyServer serverConfig(HttpProxyServerConfig serverConfig) {
		this.serverConfig = serverConfig;
		return this;
	}

	public HttpProxyServer proxyInterceptInitializer(HttpProxyInterceptInitializer proxyInterceptInitializer) {
		this.proxyInterceptInitializer = proxyInterceptInitializer;
		return this;
	}

	public HttpProxyServer httpProxyExceptionHandle(HttpProxyExceptionHandle httpProxyExceptionHandle) {
		this.httpProxyExceptionHandle = httpProxyExceptionHandle;
		return this;
	}

	public HttpProxyServer proxyConfig(ProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
		return this;
	}

	public void start(int port) {
		InternalLoggerFactory.setDefaultFactory(new Log4j2LoggerFactory());
		EventLoopGroup bossGroup = new NioEventLoopGroup();
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 100).handler(new LoggingHandler(LogLevel.DEBUG)).childHandler(new ChannelInitializer<Channel>() {

				@Override
				protected void initChannel(Channel ch) throws Exception {
					ch.pipeline().addLast("logging", new LoggingHandler(LogLevel.DEBUG));
					ch.pipeline().addLast("httpCodec", new HttpServerCodec());
					ch.pipeline().addLast("serverHandle", new HttpProxyServerHandle(serverConfig, proxyInterceptInitializer, proxyConfig, httpProxyExceptionHandle));
				}
			});
			ChannelFuture f = b.bind(port).sync();
			f.channel().closeFuture().sync();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}
	}

	public static void main(String[] args) throws Exception {
		// new HttpProxyServer().start(9999);

		new HttpProxyServer().proxyConfig(new ProxyConfig(ProxyType.HTTP, "127.0.0.1", 1087))
				// //使用socks5二级代理
				.proxyInterceptInitializer(new HttpProxyInterceptInitializer() {
					@Override
					public void init(HttpProxyInterceptPipeline pipeline) {
						pipeline.addLast(new CertDownIntercept()); // 处理证书下载
						pipeline.addLast(new HttpProxyIntercept() {
							@Override
							public void beforeRequest(Channel clientChannel, HttpRequest httpRequest, HttpProxyInterceptPipeline pipeline) throws Exception {
								// 替换UA，伪装成手机浏览器
								httpRequest.headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1");
								// 转到下一个拦截器处理
								pipeline.beforeRequest(clientChannel, httpRequest);
								HttpHeaders header = pipeline.getHttpRequest().headers();
								List<Entry<String, String>> entrys = header.entries();
								for (Entry<String, String> entry : entrys) {
									System.out.println("key = " + entry.getKey() + " value = " + entry.getValue());
								}
								boolean isImage = StringUtils.equalsAny("json", header.get("Accept"));
								header.add("is_json", (isImage || StringUtils.equalsAny(header.get("Accept"), "*/*")) && StringUtils.contains(httpRequest.uri(), "/address/"));
								if (StringUtils.contains(httpRequest.uri(), "/address/"))
									System.out.println(httpRequest.uri());
							}

							@Override
							public void beforeRequest(Channel clientChannel, HttpContent httpContent, HttpProxyInterceptPipeline pipeline) throws Exception {
								HttpHeaders header = pipeline.getHttpRequest().headers();
								if (BooleanUtils.toBoolean(header.get("is_json"))) {
									String content = httpContent.copy().content().toString(Charset.forName("utf8"));
									System.out.println(content);
								}
								super.beforeRequest(clientChannel, httpContent, pipeline);
							}

							@Override
							public void afterResponse(Channel clientChannel, Channel proxyChannel, HttpContent httpContent, HttpProxyInterceptPipeline pipeline) throws Exception {
								HttpRequest request = pipeline.getHttpRequest();
								HttpHeaders header = request.headers();
								if (BooleanUtils.toBoolean(header.get("is_json"))) {
									String content = httpContent.copy().content().toString(Charset.forName("utf8"));
									System.out.println(request.uri() + " >>>>> " + content);
								}
								// 拦截响应，添加一个响应头
								// pipeline.getHttpRequest().headers().add("intercept", "test");
								// 转到下一个拦截器处理
								pipeline.afterResponse(clientChannel, proxyChannel, httpContent);
							}
						});
					}
				}).httpProxyExceptionHandle(new HttpProxyExceptionHandle() {
					@Override
					public void beforeCatch(Channel clientChannel, Throwable cause) {
						super.beforeCatch(clientChannel, cause);
					}

					@Override
					public void afterCatch(Channel clientChannel, Channel proxyChannel, Throwable cause) {
						super.afterCatch(clientChannel, proxyChannel, cause);
					}
				}).start(8888);
	}

}
