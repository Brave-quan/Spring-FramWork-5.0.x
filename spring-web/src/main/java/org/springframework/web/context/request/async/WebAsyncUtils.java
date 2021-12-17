/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.web.context.request.async;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * Utility methods related to processing asynchronous web requests.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.2
 */
public abstract class WebAsyncUtils {

	public static final String WEB_ASYNC_MANAGER_ATTRIBUTE =
			WebAsyncManager.class.getName() + ".WEB_ASYNC_MANAGER";


	/**
	 * Obtain the {@link WebAsyncManager} for the current request, or if not
	 * found, create and associate it with the request.
	 *
	 * 获取当前请求的{@链接WebAsyncManager}，如果没有
	 * 找到，创建并将其与请求关联。
	 *
	 *HttpServletRequestImp  extends  HttpServletRequest  extends ServletRequest
	 *ServletRequestAttributeListener extends EventListener
	 *ImmediateInstanceHandle<T>   implements   InstanceHandle<T>
	 *
	 * 先看request对象里的attributes是否存在WebAsyncManager对象
	 * 有直接取，没有new一个WebAsyncManager对象之后，调用servletRequest.setAttribute方法
	 * 把WebAsyncManager对象put进ManagedListener[K,V]数组，put结果返回WebAsyncManager对象，
	 * 返回WebAsyncManager对象判断是否存在,存在就便利数组ManagedListener[]，
	 * 循环调用ServletRequestAttributeListener.attributeReplaced 来通知服务请求的 属性的状态发生改变
	 * 最后返回
	 *
	 *
	 *调用HttpServletRequestImp的setAttribute方法，
	 * 如果没有对象是null，就removeAttribute，
	 * ServletRequestAttributeListener中有ManagedListener[]数组
	 *
	 * ManagedListener.instance()
	 * ManagedListener中有 InstanceHandle<? extends EventListener> handle
	 * 不存在就调用 ManagedListener.start()构造
	 * 存在实现类ImmediateInstanceHandle.getInstance()返回EventListener对象（强转ServletRequestAttributeListener）
	 *
	 *
	 * ServletRequestAttributeListener.attributeReplaced   接收 ServletRequest 上的属性已被替换的通知。
	 * ServletRequestAttributeListener用于 创建实现类 来通知服务请求的 属性的状态发生改变
	 *

	 *
	 *
	 */
	public static WebAsyncManager getAsyncManager(ServletRequest servletRequest) {
		WebAsyncManager asyncManager = null;

		// HttpServletRequestImpl下的 Map<String, Object> attribute
		Object asyncManagerAttr = servletRequest.getAttribute(WEB_ASYNC_MANAGER_ATTRIBUTE);
		if (asyncManagerAttr instanceof WebAsyncManager) {
			asyncManager = (WebAsyncManager) asyncManagerAttr;
		}
		if (asyncManager == null) {
			asyncManager = new WebAsyncManager();
			servletRequest.setAttribute(WEB_ASYNC_MANAGER_ATTRIBUTE, asyncManager);
		}
		return asyncManager;
	}

	/**
	 * Obtain the {@link WebAsyncManager} for the current request, or if not
	 * found, create and associate it with the request.
	 */
	public static WebAsyncManager getAsyncManager(WebRequest webRequest) {
		int scope = RequestAttributes.SCOPE_REQUEST;
		WebAsyncManager asyncManager = null;
		Object asyncManagerAttr = webRequest.getAttribute(WEB_ASYNC_MANAGER_ATTRIBUTE, scope);
		if (asyncManagerAttr instanceof WebAsyncManager) {
			asyncManager = (WebAsyncManager) asyncManagerAttr;
		}
		if (asyncManager == null) {
			asyncManager = new WebAsyncManager();
			webRequest.setAttribute(WEB_ASYNC_MANAGER_ATTRIBUTE, asyncManager, scope);
		}
		return asyncManager;
	}

	/**
	 * Create an AsyncWebRequest instance. By default, an instance of
	 * {@link StandardServletAsyncWebRequest} gets created.
	 * @param request the current request
	 * @param response the current response
	 * @return an AsyncWebRequest instance (never {@code null})
	 */
	public static AsyncWebRequest createAsyncWebRequest(HttpServletRequest request, HttpServletResponse response) {
		return new StandardServletAsyncWebRequest(request, response);
	}

}
