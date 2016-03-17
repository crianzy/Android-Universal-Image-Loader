/*******************************************************************************
 * Copyright 2011-2014 Sergey Tarasevich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.core;

import android.view.View;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FlushedInputStream;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ImageLoader} engine which responsible for {@linkplain LoadAndDisplayImageTask display task} execution.
 *
 * 图片加载引擎
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.7.1
 */
class ImageLoaderEngine {

	final ImageLoaderConfiguration configuration;

	/**
	 * 下载图片的线程池
	 */
	private Executor taskExecutor;
	/**
	 * 从缓存中获取图片 的 线程池
	 */
	private Executor taskExecutorForCachedImages;
	/**
	 * taskDistributor 分发线程池, 在这个线程池中 判断 使用 上面 那个线程池
	 */
	private Executor taskDistributor;

	/**
	 * cacheKey 与 ImageAwares 对应的map 貌似还一个一个 同步的HashMap
	 */
	private final Map<Integer, String> cacheKeysForImageAwares = Collections
			.synchronizedMap(new HashMap<Integer, String>());

	/**
	 * 一看到 是虚引用 就会清楚 软引用 是内存不足时 会清楚
	 *
	 * 一个锁与 uri 对应的 map
	 */
	private final Map<String, ReentrantLock> uriLocks = new WeakHashMap<String, ReentrantLock>();

	//AtomicBoolean  原子 的 bool 对象 即在噶变 bool 的时候 其他线程不能对其修改
	//在这个Boolean值的变化的时候不允许在之间插入，保持操作的原子性
	private final AtomicBoolean paused = new AtomicBoolean(false);
	private final AtomicBoolean networkDenied = new AtomicBoolean(false);
	private final AtomicBoolean slowNetwork = new AtomicBoolean(false);

	//暂停锁
	private final Object pauseLock = new Object();

	// 构造方法
	ImageLoaderEngine(ImageLoaderConfiguration configuration) {
		this.configuration = configuration;
		// 设置 下载图片的线程池
		taskExecutor = configuration.taskExecutor;
		// 设置 获取缓存图片的线程池
		taskExecutorForCachedImages = configuration.taskExecutorForCachedImages;

		// 初始化 分发 线程池  是一个 缓存线程池
		taskDistributor = DefaultConfigurationFactory.createTaskDistributor();
	}

	/** Submits task to execution pool
	 *
	 * 提交任务 加载和 显示任务
	 * */
	void submit(final LoadAndDisplayImageTask task) {
		taskDistributor.execute(new Runnable() {
			@Override
			public void run() {
				// 看是否能够从 diskCache缓存中获取图片
				// 注意,  从 内存中获取图片的过程  在 ImageLoader 的displayImage 方法中 就调用了,  因为从内存中获取 图片很快 不需要 走线程
				File image = configuration.diskCache.get(task.getLoadingUri());
				boolean isImageCachedOnDisk = image != null && image.exists();
				// 执行前的初始化
				initExecutorsIfNeed();
				if (isImageCachedOnDisk) {
					// 如果有缓存的图片 则 使用 taskExecutorForCachedImages 执行任务
					taskExecutorForCachedImages.execute(task);
				} else {
					// 执行 下载 图片和缓存的任务
					taskExecutor.execute(task);
				}
			}
		});
	}

	/** Submits task to execution pool
	 * 提交 图片处理任务
	 * */
	void submit(ProcessAndDisplayImageTask task) {
		initExecutorsIfNeed();
		taskExecutorForCachedImages.execute(task);
	}

	private void initExecutorsIfNeed() {
		// 判断 默认的线程是否被关闭了, 如果被关闭了 那么就创新创建一个

		if (!configuration.customExecutor && ((ExecutorService) taskExecutor).isShutdown()) {
			// 如果 不是自定义的 线程池  && 线程池被 关闭了
			// 那么久 重新 创建一个线程池
			taskExecutor = createTaskExecutor();
		}
		if (!configuration.customExecutorForCachedImages && ((ExecutorService) taskExecutorForCachedImages)
				.isShutdown()) {
			taskExecutorForCachedImages = createTaskExecutor();
		}
	}

	private Executor createTaskExecutor() {
		return DefaultConfigurationFactory
				.createExecutor(configuration.threadPoolSize, configuration.threadPriority,
				configuration.tasksProcessingType);
	}

	/**
	 * Returns URI of image which is loading at this moment into passed {@link com.nostra13.universalimageloader.core.imageaware.ImageAware}
	 * 更具 ImageAware 获取 memoryCacheKey 注意这里拿到的memoryCacheKey 不是url
	 *
	 */
	String getLoadingUriForView(ImageAware imageAware) {
		return cacheKeysForImageAwares.get(imageAware.getId());
	}

	/**
	 * Associates <b>memoryCacheKey</b> with <b>imageAware</b>. Then it helps to define image URI is loaded into View at
	 * exact moment.
	 * 往 map 中 put imageAware  和 memoryCacheKey
	 * 下载完图片后. 要显示的View 都在  cacheKeysForImageAwares中
	 */
	void prepareDisplayTaskFor(ImageAware imageAware, String memoryCacheKey) {
		cacheKeysForImageAwares.put(imageAware.getId(), memoryCacheKey);
	}

	/**
	 * Cancels the task of loading and displaying image for incoming <b>imageAware</b>.
	 *
	 * 取消每个View 的显示
	 * @param imageAware {@link com.nostra13.universalimageloader.core.imageaware.ImageAware} for which display task
	 *                   will be cancelled
	 */
	void cancelDisplayTaskFor(ImageAware imageAware) {
		cacheKeysForImageAwares.remove(imageAware.getId());
	}

	/**
	 * Denies or allows engine to download images from the network.<br /> <br /> If downloads are denied and if image
	 * isn't cached then {@link ImageLoadingListener#onLoadingFailed(String, View, FailReason)} callback will be fired
	 * with {@link FailReason.FailType#NETWORK_DENIED}
	 *
	 * 设置 是否 允许通过网络下载图片
	 *
	 * @param denyNetworkDownloads pass <b>true</b> - to deny engine to download images from the network; <b>false</b> -
	 *                             to allow engine to download images from network.
	 */
	void denyNetworkDownloads(boolean denyNetworkDownloads) {
		networkDenied.set(denyNetworkDownloads);
	}

	/**
	 * Sets option whether ImageLoader will use {@link FlushedInputStream} for network downloads to handle <a
	 * href="http://code.google.com/p/android/issues/detail?id=6066">this known problem</a> or not.
	 *
	 * 设置是否在 慢速网络状态下
	 *
	 * @param handleSlowNetwork pass <b>true</b> - to use {@link FlushedInputStream} for network downloads; <b>false</b>
	 *                          - otherwise.
	 */
	void handleSlowNetwork(boolean handleSlowNetwork) {
		slowNetwork.set(handleSlowNetwork);
	}

	/**
	 * Pauses engine. All new "load&display" tasks won't be executed until ImageLoader is {@link #resume() resumed}.<br
	 * /> Already running tasks are not paused.
	 *
	 * 设置 暂停  这里只是设置 一个 bool 值 具体的暂停 应该是 个线程 会读取这个值
	 */
	void pause() {
		paused.set(true);
	}

	/** Resumes engine work. Paused "load&display" tasks will continue its work.
	 *
	 * 设置 暂停后重新启动,
	 *
	 * */
	void resume() {
		paused.set(false);
		synchronized (pauseLock) {
			// 通知重新启动
			pauseLock.notifyAll();
		}
	}

	/**
	 * Stops engine, cancels all running and scheduled display image tasks. Clears internal data.
	 * <br />
	 * <b>NOTE:</b> This method doesn't shutdown
	 * {@linkplain com.nostra13.universalimageloader.core.ImageLoaderConfiguration.Builder#taskExecutor(java.util.concurrent.Executor)
	 * custom task executors} if you set them.
	 */
	void stop() {
		// 入股不是自定义的线程 那么久关闭
		// 所以 还是用他们给的线程好一些  不用自己维护
		// 自定义的线程  得熟悉源码
		if (!configuration.customExecutor) {
			((ExecutorService) taskExecutor).shutdownNow();
		}
		if (!configuration.customExecutorForCachedImages) {
			((ExecutorService) taskExecutorForCachedImages).shutdownNow();
		}
		// 清理相关数据
		cacheKeysForImageAwares.clear();
		uriLocks.clear();
	}

	void fireCallback(Runnable r) {
		taskDistributor.execute(r);
	}

	/**
	 * 每个 Uri 都对应一把锁
	 * @param uri
	 * @return
	 */
	ReentrantLock getLockForUri(String uri) {
		ReentrantLock lock = uriLocks.get(uri);
		if (lock == null) {
			lock = new ReentrantLock();
			uriLocks.put(uri, lock);
		}
		return lock;
	}

	AtomicBoolean getPause() {
		return paused;
	}

	Object getPauseLock() {
		return pauseLock;
	}

	boolean isNetworkDenied() {
		return networkDenied.get();
	}

	boolean isSlowNetwork() {
		return slowNetwork.get();
	}
}
