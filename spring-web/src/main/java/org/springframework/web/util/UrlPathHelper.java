/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.util;

import java.net.URLDecoder;
import java.nio.charset.UnsupportedCharsetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Helper class for URL path matching. Provides support for URL paths in
 * {@code RequestDispatcher} includes and support for consistent URL decoding.
 *
 * <p>Used by {@link org.springframework.web.servlet.handler.AbstractUrlHandlerMapping}
 * and {@link org.springframework.web.servlet.support.RequestContext} for path matching
 * and/or URI determination.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Rossen Stoyanchev
 * @see #getLookupPathForRequest
 * @see javax.servlet.RequestDispatcher
 * @since 14.01.2004
 */
public class UrlPathHelper {

	/**
	 * Special WebSphere request attribute, indicating the original request URI.
	 * Preferable over the standard Servlet 2.4 forward attribute on WebSphere,
	 * simply because we need the very first URI in the request forwarding chain.
	 */
	private static final String WEBSPHERE_URI_ATTRIBUTE = "com.ibm.websphere.servlet.uri_non_decoded";

	private static final Log logger = LogFactory.getLog(UrlPathHelper.class);

	@Nullable
	static volatile Boolean websphereComplianceFlag;


	private boolean alwaysUseFullPath = false;

	private boolean urlDecode = true;

	private boolean removeSemicolonContent = true;

	private String defaultEncoding = WebUtils.DEFAULT_CHARACTER_ENCODING;

	private boolean readOnly = false;


	/**
	 * Whether URL lookups should always use the full path within the current
	 * web application context, i.e. within
	 * {@link javax.servlet.ServletContext#getContextPath()}.
	 * <p>If set to {@literal false} the path within the current servlet mapping
	 * is used instead if applicable (i.e. in the case of a prefix based Servlet
	 * mapping such as "/myServlet/*").
	 * <p>By default this is set to "false".
	 */
	public void setAlwaysUseFullPath(boolean alwaysUseFullPath) {
		checkReadOnly();
		this.alwaysUseFullPath = alwaysUseFullPath;
	}

	/**
	 * Whether the context path and request URI should be decoded -- both of
	 * which are returned <i>undecoded</i> by the Servlet API, in contrast to
	 * the servlet path.
	 * <p>Either the request encoding or the default Servlet spec encoding
	 * (ISO-8859-1) is used when set to "true".
	 * <p>By default this is set to {@literal true}.
	 * <p><strong>Note:</strong> Be aware the servlet path will not match when
	 * compared to encoded paths. Therefore use of {@code urlDecode=false} is
	 * not compatible with a prefix-based Servlet mapping and likewise implies
	 * also setting {@code alwaysUseFullPath=true}.
	 *
	 * @see #getServletPath
	 * @see #getContextPath
	 * @see #getRequestUri
	 * @see WebUtils#DEFAULT_CHARACTER_ENCODING
	 * @see javax.servlet.ServletRequest#getCharacterEncoding()
	 * @see java.net.URLDecoder#decode(String, String)
	 */
	public void setUrlDecode(boolean urlDecode) {
		checkReadOnly();
		this.urlDecode = urlDecode;
	}

	/**
	 * Whether to decode the request URI when determining the lookup path.
	 *
	 * @since 4.3.13
	 */
	public boolean isUrlDecode() {
		return this.urlDecode;
	}

	/**
	 * Set if ";" (semicolon) content should be stripped from the request URI.
	 * <p>Default is "true".
	 */
	public void setRemoveSemicolonContent(boolean removeSemicolonContent) {
		checkReadOnly();
		this.removeSemicolonContent = removeSemicolonContent;
	}

	/**
	 * Whether configured to remove ";" (semicolon) content from the request URI.
	 */
	public boolean shouldRemoveSemicolonContent() {
		checkReadOnly();
		return this.removeSemicolonContent;
	}

	/**
	 * Set the default character encoding to use for URL decoding.
	 * Default is ISO-8859-1, according to the Servlet spec.
	 * <p>If the request specifies a character encoding itself, the request
	 * encoding will override this setting. This also allows for generically
	 * overriding the character encoding in a filter that invokes the
	 * {@code ServletRequest.setCharacterEncoding} method.
	 * @param defaultEncoding the character encoding to use
	 * @see #determineEncoding
	 * @see javax.servlet.ServletRequest#getCharacterEncoding()
	 * @see javax.servlet.ServletRequest#setCharacterEncoding(String)
	 * @see WebUtils#DEFAULT_CHARACTER_ENCODING
	 */
	public void setDefaultEncoding(String defaultEncoding) {
		checkReadOnly();
		this.defaultEncoding = defaultEncoding;
	}

	/**
	 * Return the default character encoding to use for URL decoding.
	 */
	protected String getDefaultEncoding() {
		return this.defaultEncoding;
	}

	/**
	 * Switch to read-only mode where further configuration changes are not allowed.
	 */
	private void setReadOnly() {
		this.readOnly = true;
	}

	private void checkReadOnly() {
		Assert.isTrue(!this.readOnly, "This instance cannot be modified");
	}


	/**
	 * Return the mapping lookup path for the given request, within the current
	 * servlet mapping if applicable, else within the web application.
	 * <p>Detects include request URL if called within a RequestDispatcher include.
	 *
	 * 返回给定请求的映射查找路径，在当前
	 * servlet映射如果适用，或者在web应用程序。
	 * 检测包含请求URL，如果调用RequestDispatcher include
	 * @param request current HTTP request
	 * @return the lookup path
	 * @see #getPathWithinServletMapping
	 * @see #getPathWithinApplication
	 */
	public String getLookupPathForRequest(HttpServletRequest request) {
		// Always use full path within current servlet context?
		//是否总是使用全路径
		if (this.alwaysUseFullPath) {
			//结果：1、requestUri  2、"/"  3、requestUri比contextPath多出来的字符串
			return getPathWithinApplication(request);
		}
		// Else, use path within current servlet mapping if applicable
		//不是使用全路径，如果适用，在当前servlet映射中使用path
		String rest = getPathWithinServletMapping(request);
		if (!"".equals(rest)) {
			return rest;
		} else {
			//结果：1、requestUri  2、"/"  3、requestUri比contextPath多出来的字符串
			return getPathWithinApplication(request);
		}
	}

	/**
	 * Return the path within the servlet mapping for the given request,
	 * i.e. the part of the request's URL beyond the part that called the servlet,
	 * or "" if the whole URL has been used to identify the servlet.
	 * <p>Detects include request URL if called within a RequestDispatcher include.
	 * <p>
	 * 返回给定请求的servlet映射中的路径，
	 * 例如，请求的URL部分，除了调用servlet的部分，
	 * 如果整个URL被用来标识servlet，则使用“”。
	 * 检测包含请求URL，如果调用RequestDispatcher include
	 * <p>E.g.: servlet mapping = "/*"; request URI = "/test/a" -> "/test/a".
	 * <p>E.g.: servlet mapping = "/"; request URI = "/test/a" -> "/test/a".
	 * <p>E.g.: servlet mapping = "/test/*"; request URI = "/test/a" -> "/a".
	 * <p>E.g.: servlet mapping = "/test"; request URI = "/test" -> "".
	 * <p>E.g.: servlet mapping = "/*.test"; request URI = "/a.test" -> "".
	 *
	 * @param request current HTTP request
	 * @return the path within the servlet mapping, or ""
	 * @see #getLookupPathForRequest
	 */
	public String getPathWithinServletMapping(HttpServletRequest request) {
		//获取path在应用（项目）内的路径
		//结果：1、requestUri  2、"/"  3、(正确)requestUri比contextPath多出来的字符串
		String pathWithinApp = getPathWithinApplication(request);

		/*
		* 1、先从(HttpServletRequestImp)request的Map类型attributes变量中获取servletPath
		* 2、若attributes没有，就去(HttpServletRequestImp)request中取
		* 	 先获得HttpServerExchange对象，
		* 	 然后调用（HttpServerExchange）exchange对象继承的 getAttachment(附件) 方法，
		*    从父类AbstractAttachable 的Map类型 变量attachments中，
	    *    通过(HttpServletRequestImp)request中定义的不可变常量 （key）获取ServletRequestContext对象，
		*    再通过ServletRequestContext的getServletPathMatch()获得ServletPathMatch对象
	    *       ServletPathMatch 对象不为 null，调用getMatched()获得 String类型的match
	    *       否则就返回""
	    * 3、servletPath最后存在斜杠要去除
		*/
		String servletPath = getServletPath(request);
		// 将所有“//”替换为“/”
		String sanitizedPathWithinApp = getSanitizedPath(pathWithinApp);
		String path;

		// If the app container sanitized the servletPath, check against the sanitized version
		//如果应用程序容器清理了servletPath，请检查清理后的版本
		if (servletPath.contains(sanitizedPathWithinApp)) {

			/*
			 * 获取剩余的路径
			 * 分析两个字符串
			 *
			 * 1、长度一致时：
			 * 字符不相同（不忽略大小写），返回null
			 * 字符相同（不忽略大小写），返回空字符串（""）
			 * 2、长度不一致时：
			 * 短的字符串与长的存在字符不相同（不忽略大小写），返回null
			 * 短的字符串与长的存在字符相同（不忽略大小写）：
			 * 						requestUri短时，返回requestUri多出来的字符串
			 * 						requestUri长时，返回null
			 * */
			path = getRemainingPath(sanitizedPathWithinApp, servletPath, false);
		} else {
			path = getRemainingPath(pathWithinApp, servletPath, false);
		}

		if (path != null) {
			// Normal case: URI contains servlet path.
			// 正常情况:URI包含servlet路径 ->  servletPath包含pathWithinApp
			return path;
		} else {

			// Special case: URI is different from servlet path.
			// 特殊情况：URI与servlet路径不同 -> servletPath不同于pathWithinApp
			String pathInfo = request.getPathInfo();
			if (pathInfo != null) {
				// Use path info if available. Indicates index page within a servlet mapping?
				// e.g. with index page: URI="/", servletPath="/index.html"

				//如果可用，请使用路径信息。在servlet映射中指示索引页?
				//例如，使用索引页:URI="/"， servletPath="/index.html"
				return pathInfo;
			}
			if (!this.urlDecode) {
				// No path info... (not mapped by prefix, nor by extension, nor "/*")
				// For the default servlet mapping (i.e. "/"), urlDecode=false can
				// cause issues since getServletPath() returns a decoded path.
				// If decoding pathWithinApp yields a match just use pathWithinApp.
				// 没有路径信息…(既不是前缀，也不是扩展，也不是"/*")
				// 对于默认的servlet映射“/”, urlDecode = false
				// 因为getServletPath()返回一个解码的路径。
				// 如果解码pathWithinApp产生匹配，只需使用pathWithinApp。
				path = getRemainingPath(decodeInternal(request, pathWithinApp), servletPath, false);
				if (path != null) {
					return pathWithinApp;
				}
			}
			// Otherwise, use the full servlet path.
			return servletPath;
		}
	}

	/**
	 * Return the path within the web application for the given request.
	 * <p>Detects include request URL if called within a RequestDispatcher include.
	 * <p>
	 * 返回给定请求在web应用程序中的路径(其实就是url中的接口路径+方法路径)
	 * 如果在RequestDispatcher include中调用，检测包含请求URL。
	 *
	 * @param request current HTTP request
	 * @return the path within the web application
	 * @see #getLookupPathForRequest
	 */
	public String getPathWithinApplication(HttpServletRequest request) {
		//将url通过给定或默认的编码格式解析成字符串（url中的接口路径+方法路径)
		String contextPath = getContextPath(request);
		//获取请求的requestUri->资源名称部分，即位于URL的主机和端口之后、参数部分之前的部分
		String requestUri = getRequestUri(request);

		/*
		 * 获取剩余的路径
		 * 分析两个字符串
		 *
		 * 1、长度一致时：
		 * 存在字符不相同（忽略大小写），返回null
		 * 字符相同（忽略大小写），返回空字符串（""）
		 * 2、长度不一致时：
		 * 短的字符串与长的存在字符不相同返回null
		 * 短的字符串与长的存在字符相同：
		 * 						requestUri短时，返回requestUri多出来的字符串
		 * 						requestUri长时，返回null
		 *
		 * */
		String path = getRemainingPath(requestUri, contextPath, true);

		//返回结果：1、requestUri  2、"/"  3、requestUri比contextPath多出来的字符串
		if (path != null) {
			// Normal case: URI contains context path.
			//StringUtils.hasText() 字符序列的长度大于 0 ,并且不含有空白字符序列
			return (StringUtils.hasText(path) ? path : "/");
		} else {
			return requestUri;
		}
	}

	/**
	 * Match the given "mapping" to the start of the "requestUri" and if there
	 * is a match return the extra part. This method is needed because the
	 * context path and the servlet path returned by the HttpServletRequest are
	 * stripped of semicolon content unlike the requesUri.
	 * <p>
	 * 匹配给定的“映射”到“requestUri”的开始，如果有
	 * 一个匹配返回的额外部分。之所以需要这种方法，是因为
	 * 上下文路径和HttpServletRequest返回的servlet路径是
	 * 剥离分号内容不同于requesturi。
	 */
	@Nullable
	private String getRemainingPath(String requestUri, String mapping, boolean ignoreCase) {
		int index1 = 0;
		int index2 = 0;
		//如果两个长度不等，以字符串长度最小的作为标准
		//两个长度相等
		for (; (index1 < requestUri.length()) && (index2 < mapping.length()); index1++, index2++) {
			char c1 = requestUri.charAt(index1);
			char c2 = mapping.charAt(index2);
			//对requestUri做了所有分号字符的过滤，不会走这个判断
			if (c1 == ';') {
				//返回指定子串的第一次出现的字符串中的索引，从指定的索引开始。。
				index1 = requestUri.indexOf('/', index1);
				if (index1 == -1) {
					return null;
				}
				c1 = requestUri.charAt(index1);
			}
			if (c1 == c2 || (ignoreCase && (Character.toLowerCase(c1) == Character.toLowerCase(c2)))) {
				continue;
			}
			/*
			* ignoreCase 为true(忽略大小写)
			* 两个长度一致时，/main   /masn
			* ContextPath与RequestURI不相同

			* 两个长度不一致   /main   /mas
			* 以长度短的为基础，存在字符不相同
			* */
			/*
			 * ignoreCase 为 false（不忽略大小写）
			 * 两个长度一致时，
			 * ContextPath与RequestURI不相同

			 * 两个长度不一致
			 * 以长度短的为基础，若存在字符不相同或大小写不相同   /main   /mas
			 * */
			return null;
		}
		//两个长度不一致,以短的为基础，若存在字符都相同
		// 只有 ContextPath（长）  /main 比RequestURI（短）  /mai的长
		if (index2 != mapping.length()) {
			return null;
		}
		//两个长度一致时，字符都相同  /main  /main
		else if (index1 == requestUri.length()) {
			return "";
		}
		//获取";"后面第一个"/"的索引位置
		else if (requestUri.charAt(index1) == ';') {
			index1 = requestUri.indexOf('/', index1);
		}
		//两个长度不一致,以短的为基础，若存在字符都相同
		// 此时RequestURI（长） /main 比ContextPath（短）  /mai的长
		return (index1 != -1 ? requestUri.substring(index1) : "");
	}

	/**
	 * Sanitize the given path. Uses the following rules:
	 * 清理给定路径。使用以下规则:
	 * <ul>
	 * <li>replace all "//" by "/"</li>
	 * 将所有“//”替换为“/”
	 * </ul>
	 */
	private String getSanitizedPath(final String path) {
		String sanitized = path;
		while (true) {
			int index = sanitized.indexOf("//");
			if (index < 0) {
				break;
			} else {
				sanitized = sanitized.substring(0, index) + sanitized.substring(index + 1);
			}
		}
		return sanitized;
	}

	/**
	 * Return the request URI for the given request, detecting an include request
	 * URL if called within a RequestDispatcher include.
	 * <p>As the value returned by {@code request.getRequestURI()} is <i>not</i>
	 * decoded by the servlet container, this method will decode it.
	 * <p>The URI that the web container resolves <i>should</i> be correct, but some
	 * containers like JBoss/Jetty incorrectly include ";" strings like ";jsessionid"
	 * in the URI. This method cuts off such incorrect appendices.
	 * @param request current HTTP request
	 * @return the request URI
	 */
	public String getRequestUri(HttpServletRequest request) {
		String uri = (String) request.getAttribute(WebUtils.INCLUDE_REQUEST_URI_ATTRIBUTE);
		if (uri == null) {
			uri = request.getRequestURI();
		}
		return decodeAndCleanUriString(request, uri);
	}

	/**
	 * Return the context path for the given request, detecting an include request
	 * URL if called within a RequestDispatcher include.
	 * <p>As the value returned by {@code request.getContextPath()} is <i>not</i>
	 * decoded by the servlet container, this method will decode it.
	 * @param request current HTTP request
	 * @return the context path
	 */
	public String getContextPath(HttpServletRequest request) {
		// HttpServletRequestImpl的Map<String, Object> attributes，这个Map存放着请求头、请求数据
		String contextPath = (String) request.getAttribute(WebUtils.INCLUDE_CONTEXT_PATH_ATTRIBUTE);
		if (contextPath == null) {
			contextPath = request.getContextPath();
		}
		if ("/".equals(contextPath)) {
			// Invalid case, but happens for includes on Jetty: silently adapt it.
			//无效的情况，但发生在Jetty上的包括:静默地调整它。
			contextPath = "";
		}
		//将url通过给定或默认的编码格式解析成字符串
		return decodeRequestString(request, contextPath);
	}

	/**
	 * Return the servlet path for the given request, regarding an include request
	 * URL if called within a RequestDispatcher include.
	 * <p>As the value returned by {@code request.getServletPath()} is already
	 * decoded by the servlet container, this method will not attempt to decode it.
	 * <p>
	 * 返回给定请求的servlet路径，关于包含请求
	 * 如果在RequestDispatcher include中调用URL。
	 * {@code request.getServletPath()}返回的值已经是
	 * 被servlet容器解码，这个方法不会尝试解码它。
	 *
	 * @param request current HTTP request
	 * @return the servlet path
	 */
	public String getServletPath(HttpServletRequest request) {
		//从HttpServletRequestImp的 Map—> attributes 属性
		String servletPath = (String) request.getAttribute(WebUtils.INCLUDE_SERVLET_PATH_ATTRIBUTE);
		if (servletPath == null) {
			/*
			 * 若attributes没有，就去(HttpServletRequestImp)request中取
			 * 先获得HttpServerExchange对象，
			 * 然后调用（HttpServerExchange）exchange对象继承的 getAttachment(附件) 方法，
			 * 从父类AbstractAttachable 的Map类型 变量attachments中，
			 * 通过(HttpServletRequestImp)request中定义的不可变常量 （key）获取ServletRequestContext对象，
			 * 再通过ServletRequestContext的getServletPathMatch()获得ServletPathMatch对象
			 * ServletPathMatch对象不为null，调用getMatched()获得 String类型的match
			 * 否则就返回""
			 *
			 * */
			servletPath = request.getServletPath();
		}
		//通俗点就是删除最后多余的尾随斜杠"/"
		if (servletPath.length() > 1 && servletPath.endsWith("/") && shouldRemoveTrailingServletPathSlash(request)) {
			// On WebSphere, in non-compliant mode, for a "/foo/" case that would be "/foo"
			// on all other servlet containers: removing trailing slash, proceeding with
			// that remaining slash as final lookup path...
			//在WebSphere中，在非兼容模式下，对于“/foo/”情况，则为“/foo”
			//所有其他的servlet容器:删除尾随斜杠，继续
			//剩下的斜杠作为最终的查找路径…
			servletPath = servletPath.substring(0, servletPath.length() - 1);
		}
		return servletPath;
	}


	/**
	 * Return the request URI for the given request. If this is a forwarded request,
	 * correctly resolves to the request URI of the original request.
	 */
	public String getOriginatingRequestUri(HttpServletRequest request) {
		String uri = (String) request.getAttribute(WEBSPHERE_URI_ATTRIBUTE);
		if (uri == null) {
			uri = (String) request.getAttribute(WebUtils.FORWARD_REQUEST_URI_ATTRIBUTE);
			if (uri == null) {
				uri = request.getRequestURI();
			}
		}
		return decodeAndCleanUriString(request, uri);
	}

	/**
	 * Return the context path for the given request, detecting an include request
	 * URL if called within a RequestDispatcher include.
	 * <p>As the value returned by {@code request.getContextPath()} is <i>not</i>
	 * decoded by the servlet container, this method will decode it.
	 * @param request current HTTP request
	 * @return the context path
	 */
	public String getOriginatingContextPath(HttpServletRequest request) {
		String contextPath = (String) request.getAttribute(WebUtils.FORWARD_CONTEXT_PATH_ATTRIBUTE);
		if (contextPath == null) {
			contextPath = request.getContextPath();
		}
		return decodeRequestString(request, contextPath);
	}

	/**
	 * Return the servlet path for the given request, detecting an include request
	 * URL if called within a RequestDispatcher include.
	 * @param request current HTTP request
	 * @return the servlet path
	 */
	public String getOriginatingServletPath(HttpServletRequest request) {
		String servletPath = (String) request.getAttribute(WebUtils.FORWARD_SERVLET_PATH_ATTRIBUTE);
		if (servletPath == null) {
			servletPath = request.getServletPath();
		}
		return servletPath;
	}

	/**
	 * Return the query string part of the given request's URL. If this is a forwarded request,
	 * correctly resolves to the query string of the original request.
	 * @param request current HTTP request
	 * @return the query string
	 */
	public String getOriginatingQueryString(HttpServletRequest request) {
		if ((request.getAttribute(WebUtils.FORWARD_REQUEST_URI_ATTRIBUTE) != null) ||
				(request.getAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE) != null)) {
			return (String) request.getAttribute(WebUtils.FORWARD_QUERY_STRING_ATTRIBUTE);
		} else {
			return request.getQueryString();
		}
	}

	/**
	 * Decode the supplied URI string and strips any extraneous portion after a ';'.
	 */
	private String decodeAndCleanUriString(HttpServletRequest request, String uri) {
		uri = removeSemicolonContent(uri);
		uri = decodeRequestString(request, uri);
		uri = getSanitizedPath(uri);
		return uri;
	}

	/**
	 * Decode the given source string with a URLDecoder. The encoding will be taken
	 * from the request, falling back to the default "ISO-8859-1".
	 * <p>The default implementation uses {@code URLDecoder.decode(input, enc)}.
	 * <p>
	 * 解码给定的源字符串用URLDecoder。字符串编码将被使用与请求，返回默认的到默认的“ISO-8859-1”。
	 * 默认的实现使用{@code URLDecoder.decode(input, enc)}。
	 * @param request current HTTP request
	 * @param source  the String to decode
	 * @return the decoded String
	 * @see WebUtils#DEFAULT_CHARACTER_ENCODING
	 * @see javax.servlet.ServletRequest#getCharacterEncoding
	 * @see java.net.URLDecoder#decode(String, String)
	 * @see java.net.URLDecoder#decode(String)
	 */
	public String decodeRequestString(HttpServletRequest request, String source) {
		//url是否解码
		if (this.urlDecode) {
			//通过编码格式将请求全路径（url）解析成字符串
			return decodeInternal(request, source);
		}
		return source;
	}

	@SuppressWarnings("deprecation")
	private String decodeInternal(HttpServletRequest request, String source) {
		//获取请求的字符编码格式（有就返回，没有就返回默认的）
		String enc = determineEncoding(request);
		try {
			//返回将url用给定字符编码格式解析的字符串
			return UriUtils.decode(source, enc);
		} catch (UnsupportedCharsetException ex) {
			if (logger.isWarnEnabled()) {
				logger.warn("Could not decode request string [" + source + "] with encoding '" + enc +
						"': falling back to platform default encoding; exception message: " + ex.getMessage());
			}
			//返回使用默认编码格式将url解析字符串
			return URLDecoder.decode(source);
		}
	}

	/**
	 * Determine the encoding for the given request.
	 * Can be overridden in subclasses.
	 * <p>The default implementation checks the request encoding,
	 * falling back to the default encoding specified for this resolver.
	 * <p>
	 * 确定给定请求的编码。
	 * 可以在子类中被重写。
	 * 默认实现检查请求编码，
	 * 返回为该解析器指定的默认编码。
	 * @param request current HTTP request
	 * @return the encoding for the request (never {@code null})
	 * @see javax.servlet.ServletRequest#getCharacterEncoding()
	 * @see #setDefaultEncoding
	 */
	protected String determineEncoding(HttpServletRequest request) {
		//获得请求的字符编码
		String enc = request.getCharacterEncoding();
		if (enc == null) {
			//默认字符编码 DEFAULT_CHARACTER_ENCODING = "ISO-8859-1";
			enc = getDefaultEncoding();
		}
		return enc;
	}

	/**
	 * Remove ";" (semicolon) content from the given request URI if the
	 * {@linkplain #setRemoveSemicolonContent removeSemicolonContent}
	 * property is set to "true". Note that "jsessionid" is always removed.
	 * 删除”;“(分号)内容，如果
	 * {@linkplain #setRemoveSemicolonContent removeSemicolonContent}
	 * 属性设置为“true”。注意，“jsessionid”总是被删除。
	 * @param requestUri the request URI string to remove ";" content from
	 * @return the updated URI string
	 */
	public String removeSemicolonContent(String requestUri) {
		return (this.removeSemicolonContent ?
				removeSemicolonContentInternal(requestUri) : removeJsessionid(requestUri));
	}

	private String removeSemicolonContentInternal(String requestUri) {
		int semicolonIndex = requestUri.indexOf(';');
		while (semicolonIndex != -1) {
			int slashIndex = requestUri.indexOf('/', semicolonIndex);
			String start = requestUri.substring(0, semicolonIndex);
			requestUri = (slashIndex != -1) ? start + requestUri.substring(slashIndex) : start;
			semicolonIndex = requestUri.indexOf(';', semicolonIndex);
		}
		return requestUri;
	}

	private String removeJsessionid(String requestUri) {
		String key = ";jsessionid=";
		int index = requestUri.toLowerCase().indexOf(key);
		if (index == -1) {
			return requestUri;
		}
		String start = requestUri.substring(0, index);
		for (int i = index + key.length(); i < requestUri.length(); i++) {
			char c = requestUri.charAt(i);
			if (c == ';' || c == '/') {
				return start + requestUri.substring(i);
			}
		}
		return start;
	}

	/**
	 * Decode the given URI path variables via {@link #decodeRequestString} unless
	 * {@link #setUrlDecode} is set to {@code true} in which case it is assumed
	 * the URL path from which the variables were extracted is already decoded
	 * through a call to {@link #getLookupPathForRequest(HttpServletRequest)}.
	 * @param request current HTTP request
	 * @param vars the URI variables extracted from the URL path
	 * @return the same Map or a new Map instance
	 */
	public Map<String, String> decodePathVariables(HttpServletRequest request, Map<String, String> vars) {
		if (this.urlDecode) {
			return vars;
		} else {
			Map<String, String> decodedVars = new LinkedHashMap<>(vars.size());
			vars.forEach((key, value) -> decodedVars.put(key, decodeInternal(request, value)));
			return decodedVars;
		}
	}

	/**
	 * Decode the given matrix variables via {@link #decodeRequestString} unless
	 * {@link #setUrlDecode} is set to {@code true} in which case it is assumed
	 * the URL path from which the variables were extracted is already decoded
	 * through a call to {@link #getLookupPathForRequest(HttpServletRequest)}.
	 * @param request current HTTP request
	 * @param vars the URI variables extracted from the URL path
	 * @return the same Map or a new Map instance
	 */
	public MultiValueMap<String, String> decodeMatrixVariables(
			HttpServletRequest request, MultiValueMap<String, String> vars) {

		if (this.urlDecode) {
			return vars;
		}else {
			MultiValueMap<String, String> decodedVars = new LinkedMultiValueMap<>(vars.size());
			vars.forEach((key, values) -> {
				for (String value : values) {
					decodedVars.add(key, decodeInternal(request, value));
				}
			});
			return decodedVars;
		}
	}

	private boolean shouldRemoveTrailingServletPathSlash(HttpServletRequest request) {
		if (request.getAttribute(WEBSPHERE_URI_ATTRIBUTE) == null) {
			// Regular servlet container: behaves as expected in any case,
			// so the trailing slash is the result of a "/" url-pattern mapping.
			// Don't remove that slash.
			return false;
		}
		Boolean flagToUse = websphereComplianceFlag;
		if (flagToUse == null) {
			ClassLoader classLoader = UrlPathHelper.class.getClassLoader();
			String className = "com.ibm.ws.webcontainer.WebContainer";
			String methodName = "getWebContainerProperties";
			String propName = "com.ibm.ws.webcontainer.removetrailingservletpathslash";
			boolean flag = false;
			try {
				Class<?> cl = classLoader.loadClass(className);
				Properties prop = (Properties) cl.getMethod(methodName).invoke(null);
				flag = Boolean.parseBoolean(prop.getProperty(propName));
			}catch (Throwable ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not introspect WebSphere web container properties: " + ex);
				}
			}
			flagToUse = flag;
			websphereComplianceFlag = flag;
		}
		// Don't bother if WebSphere is configured to be fully Servlet compliant.
		// However, if it is not compliant, do remove the improper trailing slash!
		return !flagToUse;
	}


	/**
	 * Shared, read-only instance with defaults. The following apply:
	 * <ul>
	 * <li>{@code alwaysUseFullPath=false}
	 * <li>{@code urlDecode=true}
	 * <li>{@code removeSemicolon=true}
	 * <li>{@code defaultEncoding=}{@link WebUtils#DEFAULT_CHARACTER_ENCODING}
	 * </ul>
	 */
	public static final UrlPathHelper defaultInstance = new UrlPathHelper();

	static {
		defaultInstance.setReadOnly();
	}


	/**
	 * Shared, read-only instance for the full, encoded path. The following apply:
	 * <ul>
	 * <li>{@code alwaysUseFullPath=true}
	 * <li>{@code urlDecode=false}
	 * <li>{@code removeSemicolon=false}
	 * <li>{@code defaultEncoding=}{@link WebUtils#DEFAULT_CHARACTER_ENCODING}
	 * </ul>
	 */
	public static final UrlPathHelper rawPathInstance = new UrlPathHelper() {

		@Override
		public String removeSemicolonContent(String requestUri) {
			return requestUri;
		}
	};

	static {
		rawPathInstance.setAlwaysUseFullPath(true);
		rawPathInstance.setUrlDecode(false);
		rawPathInstance.setRemoveSemicolonContent(false);
		rawPathInstance.setReadOnly();
	}

}
