/*******************************************************************************
 * Copyright 2011-2014 Sergey Tarasevich
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.core;

import android.graphics.Bitmap;
import android.os.Handler;

import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FailReason.FailType;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.assist.LoadedFrom;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.core.decode.ImageDecoder;
import com.nostra13.universalimageloader.core.decode.ImageDecodingInfo;
import com.nostra13.universalimageloader.core.download.ImageDownloader;
import com.nostra13.universalimageloader.core.download.ImageDownloader.Scheme;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener;
import com.nostra13.universalimageloader.utils.IoUtils;
import com.nostra13.universalimageloader.utils.L;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Presents load'n'display image task. Used to load image from Internet or file system, decode it to {@link Bitmap}, and
 * display it in {@link com.nostra13.universalimageloader.core.imageaware.ImageAware} using {@link DisplayBitmapTask}.
 * <p/>
 * 这个是下载 图片的任务
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ImageLoaderConfiguration
 * @see ImageLoadingInfo
 * @since 1.3.1
 */
final class LoadAndDisplayImageTask implements Runnable, IoUtils.CopyListener {

	private static final String LOG_WAITING_FOR_RESUME = "ImageLoader is paused. Waiting...  [%s]";
	private static final String LOG_RESUME_AFTER_PAUSE = ".. Resume loading [%s]";
	private static final String LOG_DELAY_BEFORE_LOADING = "Delay %d ms before loading...  [%s]";
	private static final String LOG_START_DISPLAY_IMAGE_TASK = "Start display image task [%s]";
	private static final String LOG_WAITING_FOR_IMAGE_LOADED = "Image already is loading. Waiting... [%s]";
	private static final String LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING = "...Get cached bitmap from memory after waiting. [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_NETWORK = "Load image from network [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_DISK_CACHE = "Load image from disk cache [%s]";
	private static final String LOG_RESIZE_CACHED_IMAGE_FILE = "Resize image in disk cache [%s]";
	private static final String LOG_PREPROCESS_IMAGE = "PreProcess image before caching in memory [%s]";
	private static final String LOG_POSTPROCESS_IMAGE = "PostProcess image before displaying [%s]";
	private static final String LOG_CACHE_IMAGE_IN_MEMORY = "Cache image in memory [%s]";
	private static final String LOG_CACHE_IMAGE_ON_DISK = "Cache image on disk [%s]";
	private static final String LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISK = "Process image before cache on disk [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEAWARE_REUSED = "ImageAware is reused for another image. Task is cancelled. [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEAWARE_COLLECTED = "ImageAware was collected by GC. Task is cancelled. [%s]";
	private static final String LOG_TASK_INTERRUPTED = "Task was interrupted [%s]";

	private static final String ERROR_NO_IMAGE_STREAM = "No stream for image [%s]";
	private static final String ERROR_PRE_PROCESSOR_NULL = "Pre-processor returned null [%s]";
	private static final String ERROR_POST_PROCESSOR_NULL = "Post-processor returned null [%s]";
	private static final String ERROR_PROCESSOR_FOR_DISK_CACHE_NULL = "Bitmap processor for disk cache returned null [%s]";

	private final ImageLoaderEngine engine;
	private final ImageLoadingInfo imageLoadingInfo;
	private final Handler handler;

	// Helper references
	private final ImageLoaderConfiguration configuration;
	private final ImageDownloader downloader;
	private final ImageDownloader networkDeniedDownloader;
	private final ImageDownloader slowNetworkDownloader;
	private final ImageDecoder decoder;
	final String uri;
	private final String memoryCacheKey;
	final ImageAware imageAware;
	private final ImageSize targetSize;
	final DisplayImageOptions options;
	final ImageLoadingListener listener;
	final ImageLoadingProgressListener progressListener;
	private final boolean syncLoading;

	// State vars
	private LoadedFrom loadedFrom = LoadedFrom.NETWORK;

	public LoadAndDisplayImageTask(ImageLoaderEngine engine, ImageLoadingInfo imageLoadingInfo, Handler handler) {
		this.engine = engine;
		this.imageLoadingInfo = imageLoadingInfo;
		this.handler = handler;

		configuration = engine.configuration;
		downloader = configuration.downloader;
		networkDeniedDownloader = configuration.networkDeniedDownloader;
		slowNetworkDownloader = configuration.slowNetworkDownloader;
		decoder = configuration.decoder;
		uri = imageLoadingInfo.uri;
		memoryCacheKey = imageLoadingInfo.memoryCacheKey;
		imageAware = imageLoadingInfo.imageAware;
		targetSize = imageLoadingInfo.targetSize;
		options = imageLoadingInfo.options;
		listener = imageLoadingInfo.listener;
		progressListener = imageLoadingInfo.progressListener;
		syncLoading = options.isSyncLoading();
	}

	@Override
	public void run() {
		if (waitIfPaused()) return;
		if (delayIfNeed()) return;

		// 获取 当前url 对应的说,  说在 engine 中产生
		ReentrantLock loadFromUriLock = imageLoadingInfo.loadFromUriLock;
		L.d(LOG_START_DISPLAY_IMAGE_TASK, memoryCacheKey);
		if (loadFromUriLock.isLocked()) {
			// 是否被锁住了
			L.d(LOG_WAITING_FOR_IMAGE_LOADED, memoryCacheKey);
		}
		// 开锁了
		loadFromUriLock.lock();
		Bitmap bmp;
		try {
			// 检查 是否被回收 和 View 的图片是否被换掉了
			checkTaskNotActual();

			// 先从 内存中获取图片
			bmp = configuration.memoryCache.get(memoryCacheKey);
			if (bmp == null || bmp.isRecycled()) {
				// 如果获取到了的话
				// 尝试 读取图片  这里会加重网络度 并缓存到磁盘上了
				bmp = tryLoadBitmap();
				//如果  bmp == null 直接 返回即可 各种错误处理在 tryLoadBitmap 以及处理了
				if (bmp == null) return; // listener callback already was fired

				// 再次检查
				checkTaskNotActual();
				checkTaskInterrupted();

				if (options.shouldPreProcess()) {
					// 预先处理图片 在缓存到内存之间处理
					L.d(LOG_PREPROCESS_IMAGE, memoryCacheKey);
					bmp = options.getPreProcessor().process(bmp);
					if (bmp == null) {
						// 处理后 bmp 有可能为空
						L.e(ERROR_PRE_PROCESSOR_NULL, memoryCacheKey);
					}
				}

				if (bmp != null && options.isCacheInMemory()) {
					// 不为空 则缓存到内存中
					L.d(LOG_CACHE_IMAGE_IN_MEMORY, memoryCacheKey);
					configuration.memoryCache.put(memoryCacheKey, bmp);
				}
			} else {
				// 如果图片从内存 缓存中读出来了
				// 设置 loadedFrom 为内存缓存
				loadedFrom = LoadedFrom.MEMORY_CACHE;
				L.d(LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING, memoryCacheKey);
			}

			if (bmp != null && options.shouldPostProcess()) {
				// 处理图片
				L.d(LOG_POSTPROCESS_IMAGE, memoryCacheKey);
				bmp = options.getPostProcessor().process(bmp);
				if (bmp == null) {
					L.e(ERROR_POST_PROCESSOR_NULL, memoryCacheKey);
				}
			}
			// 再次检测
			checkTaskNotActual();
			checkTaskInterrupted();
		} catch (TaskCancelledException e) {
			// catch 到 取消的异常 则处理
			// 主要是 回调 取消异常事件
			fireCancelEvent();
			return;
		} finally {
			// 解锁
			loadFromUriLock.unlock();
		}

		// 执行显示图片任务
		DisplayBitmapTask displayBitmapTask = new DisplayBitmapTask(bmp, imageLoadingInfo, engine, loadedFrom);
		// 多数情况下 handler 不为空 所以  displayBitmapTask 在主线程中晚餐
		//TODO 需要测试 displayBitmapTask 是否在主线程中晚餐
		runTask(displayBitmapTask, syncLoading, handler, engine);
	}

	/**
	 * @return <b>true</b> - if task should be interrupted; <b>false</b> - otherwise
	 * 如果返回 true 表示该任务需要被 中断
	 */
	private boolean waitIfPaused() {
		// 获取 引擎中的 是否暂停的状态
		AtomicBoolean pause = engine.getPause();
		if (pause.get()) {
			// 如果暂停
			synchronized (engine.getPauseLock()) {
				if (pause.get()) {
					L.d(LOG_WAITING_FOR_RESUME, memoryCacheKey);
					try {
						// 如果暂停 这里 wait  那么这个 线程 就暂停了
						// 等待 被 notify
						engine.getPauseLock().wait();
					} catch (InterruptedException e) {
						// 正常的waite 不会报这个异常
						L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
						return true;
					}
					L.d(LOG_RESUME_AFTER_PAUSE, memoryCacheKey);
				}
			}
		}
		// 是否 view 被回收 或 View 显示的图片被换掉
		return isTaskNotActual();
	}

	/**
	 * @return <b>true</b> - if task should be interrupted; <b>false</b> - otherwise
	 * 是否需要 delay
	 */
	private boolean delayIfNeed() {
		// 在loading前 是否需要delay
		if (options.shouldDelayBeforeLoading()) {
			L.d(LOG_DELAY_BEFORE_LOADING, options.getDelayBeforeLoading(), memoryCacheKey);
			try {
				Thread.sleep(options.getDelayBeforeLoading());
			} catch (InterruptedException e) {
				// 如果出现线程终端移除 就返回true
				L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
				return true;
			}
			return isTaskNotActual();
		}
		return false;
	}

	/**
	 * 加载 Bitmap 并缓存到磁盘中
	 *
	 * @return
	 * @throws TaskCancelledException
	 */
	private Bitmap tryLoadBitmap() throws TaskCancelledException {
		Bitmap bitmap = null;
		try {
			File imageFile = configuration.diskCache.get(uri);
			// 更具Uri 获取相应的缓存图片文件
			if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
				L.d(LOG_LOAD_IMAGE_FROM_DISK_CACHE, memoryCacheKey);
				// 如果文件存在
				loadedFrom = LoadedFrom.DISC_CACHE;

				// 再次检查 view 是否被回收 和View 显示的图片是否被换掉
				checkTaskNotActual();
				// 编码处Bitmap
				// Scheme.FILE.wrap(imageFile.getAbsolutePath()) 包装成file:///
				bitmap = decodeImage(Scheme.FILE.wrap(imageFile.getAbsolutePath()));
			}
			if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
				// 如果bitmap 没有从file 中读出来, 那么就从网络中获取图片
				L.d(LOG_LOAD_IMAGE_FROM_NETWORK, memoryCacheKey);
				loadedFrom = LoadedFrom.NETWORK;

				String imageUriForDecoding = uri;
				// tryCacheImageOnDisk 方法中 会去下载图片
				if (options.isCacheOnDisk() && tryCacheImageOnDisk()) {
					// 这里再根据 uri 去获取图片文件
					imageFile = configuration.diskCache.get(uri);
					if (imageFile != null) {
						// 转为 fill:/// 格式
						imageUriForDecoding = Scheme.FILE.wrap(imageFile.getAbsolutePath());
					}
				}
				// 再次检查 View 是否被回收, 图片是否被换掉
				checkTaskNotActual();
				// 根据 uri 编码bitmap
				bitmap = decodeImage(imageUriForDecoding);

				if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
					// bitmap 获取失败
					fireFailEvent(FailType.DECODING_ERROR, null);
				}
			}
		} catch (IllegalStateException e) {
			fireFailEvent(FailType.NETWORK_DENIED, null);
		} catch (TaskCancelledException e) {
			throw e;
		} catch (IOException e) {
			L.e(e);
			fireFailEvent(FailType.IO_ERROR, e);
		} catch (OutOfMemoryError e) {
			L.e(e);
			fireFailEvent(FailType.OUT_OF_MEMORY, e);
		} catch (Throwable e) {
			L.e(e);
			fireFailEvent(FailType.UNKNOWN, e);
		}
		return bitmap;
	}

	private Bitmap decodeImage(String imageUri) throws IOException {
		// 获取缩放类型
		ViewScaleType viewScaleType = imageAware.getScaleType();
		// 图片 编译信息
		ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey, imageUri, uri, targetSize, viewScaleType,
				getDownloader(), options);
		// 开始 编码图片
		return decoder.decode(decodingInfo);
	}

	/**
	 * @return <b>true</b> - if image was downloaded successfully; <b>false</b> - otherwise
	 * 返回 true 表示下载成功
	 */
	private boolean tryCacheImageOnDisk() throws TaskCancelledException {
		L.d(LOG_CACHE_IMAGE_ON_DISK, memoryCacheKey);

		boolean loaded;
		try {
			// 下载图片
			loaded = downloadImage();
			if (loaded) {
				// 下载 成功
				int width = configuration.maxImageWidthForDiskCache;
				int height = configuration.maxImageHeightForDiskCache;
				if (width > 0 || height > 0) {
					L.d(LOG_RESIZE_CACHED_IMAGE_FILE, memoryCacheKey);
					// 修改保存图片的匡高
					resizeAndSaveImage(width, height); // TODO : process boolean result
				}
			}
		} catch (IOException e) {
			L.e(e);
			loaded = false;
		}
		return loaded;
	}

	/**
	 * 下载图片
	 * 下载成功 会吧图片 存入文件缓存中
	 *
	 * @return
	 * @throws IOException
	 */
	private boolean downloadImage() throws IOException {
		InputStream is = getDownloader().getStream(uri, options.getExtraForDownloader());
		if (is == null) {
			L.e(ERROR_NO_IMAGE_STREAM, memoryCacheKey);
			return false;
		} else {
			try {
				// 这里吧 流 存入磁盘缓存中
				return configuration.diskCache.save(uri, is, this);
			} finally {
				IoUtils.closeSilently(is);
			}
		}
	}

	/**
	 * Decodes image file into Bitmap, resize it and save it back
	 * <p/>
	 * 下载成功了图片才会 调用改方法
	 */
	private boolean resizeAndSaveImage(int maxWidth, int maxHeight) throws IOException {
		// Decode image file, compress and re-save it
		boolean saved = false;
		// 直接跟url 从diskCache 获取file
		File targetFile = configuration.diskCache.get(uri);
		if (targetFile != null && targetFile.exists()) {
			// 修改宽高后 重新编码
			ImageSize targetImageSize = new ImageSize(maxWidth, maxHeight);
			DisplayImageOptions specialOptions = new DisplayImageOptions.Builder().cloneFrom(options)
					.imageScaleType(ImageScaleType.IN_SAMPLE_INT).build();
			ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey,
					Scheme.FILE.wrap(targetFile.getAbsolutePath()), uri, targetImageSize, ViewScaleType.FIT_INSIDE,
					getDownloader(), specialOptions);
			//TODO 修改 宽高后的图片 是否存到了磁盘上???  答案是没有
			// 在 decode 方法中处理后的Bitamp 没有存放到磁盘上
			Bitmap bmp = decoder.decode(decodingInfo);
			if (bmp != null && configuration.processorForDiskCache != null) {
				L.d(LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISK, memoryCacheKey);
				bmp = configuration.processorForDiskCache.process(bmp);
				if (bmp == null) {
					L.e(ERROR_PROCESSOR_FOR_DISK_CACHE_NULL, memoryCacheKey);
				}
			}
			if (bmp != null) {
				saved = configuration.diskCache.save(uri, bmp);
				bmp.recycle();
			}
		}
		return saved;
	}

	@Override
	public boolean onBytesCopied(int current, int total) {
		return syncLoading || fireProgressEvent(current, total);
	}

	/**
	 * @return <b>true</b> - if loading should be continued; <b>false</b> - if loading should be interrupted
	 */
	private boolean fireProgressEvent(final int current, final int total) {
		if (isTaskInterrupted() || isTaskNotActual()) return false;
		if (progressListener != null) {
			Runnable r = new Runnable() {
				@Override
				public void run() {
					progressListener.onProgressUpdate(uri, imageAware.getWrappedView(), current, total);
				}
			};
			runTask(r, false, handler, engine);
		}
		return true;
	}

	/**
	 * 处理图片获取失败的情况
	 *
	 * @param failType
	 * @param failCause
	 */
	private void fireFailEvent(final FailType failType, final Throwable failCause) {
		// 如果是 不在线程池中跑的 或  中断 或者 图片配换掉 view 被回收 则不做不理
		if (syncLoading || isTaskInterrupted() || isTaskNotActual()) return;
		Runnable r = new Runnable() {
			@Override
			public void run() {
				if (options.shouldShowImageOnFail()) {
					// 处理 显示失败的图片
					imageAware.setImageDrawable(options.getImageOnFail(configuration.resources));
				}
				// 回调失败
				listener.onLoadingFailed(uri, imageAware.getWrappedView(), new FailReason(failType, failCause));
			}
		};
		// 执行 处理失败的任务
		runTask(r, false, handler, engine);
	}

	/**
	 * 处理取消事件
	 */
	private void fireCancelEvent() {
		if (syncLoading || isTaskInterrupted()) return;
		Runnable r = new Runnable() {
			@Override
			public void run() {
				listener.onLoadingCancelled(uri, imageAware.getWrappedView());
			}
		};
		runTask(r, false, handler, engine);
	}

	private ImageDownloader getDownloader() {
		ImageDownloader d;
		if (engine.isNetworkDenied()) {
			d = networkDeniedDownloader;
		} else if (engine.isSlowNetwork()) {
			d = slowNetworkDownloader;
		} else {
			d = downloader;
		}
		return d;
	}

	/**
	 * @throws TaskCancelledException if task is not actual (target ImageAware is collected by GC or the image URI of
	 *                                this task doesn't match to image URI which is actual for current ImageAware at
	 *                                this moment)
	 */
	private void checkTaskNotActual() throws TaskCancelledException {
		checkViewCollected();
		checkViewReused();
	}

	/**
	 * @return <b>true</b> - if task is not actual (target ImageAware is collected by GC or the image URI of this task
	 * doesn't match to image URI which is actual for current ImageAware at this moment)); <b>false</b> - otherwise
	 */
	private boolean isTaskNotActual() {
		// 是否 view 被回收 或 View 显示的图片被换掉
		return isViewCollected() || isViewReused();
	}

	/**
	 * @throws TaskCancelledException if target ImageAware is collected
	 */
	private void checkViewCollected() throws TaskCancelledException {
		if (isViewCollected()) {
			throw new TaskCancelledException();
		}
	}

	/**
	 * @return <b>true</b> - if target ImageAware is collected by GC; <b>false</b> - otherwise
	 * <p/>
	 * 返回 View 是否被回收
	 */
	private boolean isViewCollected() {
		if (imageAware.isCollected()) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_COLLECTED, memoryCacheKey);
			return true;
		}
		return false;
	}

	/**
	 * @throws TaskCancelledException if target ImageAware is collected by GC
	 */
	private void checkViewReused() throws TaskCancelledException {
		if (isViewReused()) {
			throw new TaskCancelledException();
		}
	}

	/**
	 * @return <b>true</b> - if current ImageAware is reused for displaying another image; <b>false</b> - otherwise '
	 * <p/>
	 * 如果 返回true 表示该View 用于显示其他的图片去了
	 * 这个理检查 engine 的key 和 该task 中的 key 是否一致
	 */
	private boolean isViewReused() {
		String currentCacheKey = engine.getLoadingUriForView(imageAware);
		// Check whether memory cache key (image URI) for current ImageAware is actual.
		// If ImageAware is reused for another task then current task should be cancelled.
		boolean imageAwareWasReused = !memoryCacheKey.equals(currentCacheKey);
		if (imageAwareWasReused) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_REUSED, memoryCacheKey);
			return true;
		}
		return false;
	}

	/**
	 * @throws TaskCancelledException if current task was interrupted
	 */
	private void checkTaskInterrupted() throws TaskCancelledException {
		if (isTaskInterrupted()) {
			throw new TaskCancelledException();
		}
	}

	/**
	 * @return <b>true</b> - if current task was interrupted; <b>false</b> - otherwise
	 */
	private boolean isTaskInterrupted() {
		if (Thread.interrupted()) {
			L.d(LOG_TASK_INTERRUPTED, memoryCacheKey);
			return true;
		}
		return false;
	}

	String getLoadingUri() {
		return uri;
	}

	static void runTask(Runnable r, boolean sync, Handler handler, ImageLoaderEngine engine) {
		if (sync) {
			// 如果是 不是在线程池中 则直接执行
			r.run();
		} else if (handler == null) {
			// 如果 handler== null 则引擎处理
			engine.fireCallback(r);
		} else {
			// 多数情况下 handler 不为空
			// 如果handle 不为空 则Handler 处理
			handler.post(r);
		}
	}

	/**
	 * Exceptions for case when task is cancelled (thread is interrupted, image view is reused for another task, view is
	 * collected by GC).
	 *
	 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
	 * @since 1.9.1
	 */
	class TaskCancelledException extends Exception {
	}
}
