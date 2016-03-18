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

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import com.nostra13.universalimageloader.cache.disc.DiskCache;
import com.nostra13.universalimageloader.cache.memory.MemoryCache;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FlushedInputStream;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.assist.LoadedFrom;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;
import com.nostra13.universalimageloader.core.imageaware.ImageViewAware;
import com.nostra13.universalimageloader.core.imageaware.NonViewAware;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;
import com.nostra13.universalimageloader.utils.ImageSizeUtils;
import com.nostra13.universalimageloader.utils.L;
import com.nostra13.universalimageloader.utils.MemoryCacheUtils;

/**
 * Singletone for image loading and displaying at {@link ImageView ImageViews}<br />
 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before any other method.
 *
 *
 * 其实这个类 没有什么太多的东西  就是一个封装入口
 *
 * 同时还是个单例
 *
 * 一些 获取信息的方法 ,大部分的信息 都是  configuration 和 engine 中
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.0.0
 */
public class ImageLoader {

	public static final String TAG = ImageLoader.class.getSimpleName();

	static final String LOG_INIT_CONFIG = "Initialize ImageLoader with configuration";
	static final String LOG_DESTROY = "Destroy ImageLoader";
	static final String LOG_LOAD_IMAGE_FROM_MEMORY_CACHE = "Load image from memory cache [%s]";

	private static final String WARNING_RE_INIT_CONFIG = "Try to initialize ImageLoader which had already been initialized before. " + "To re-init ImageLoader with new configuration call ImageLoader.destroy() at first.";
	private static final String ERROR_WRONG_ARGUMENTS = "Wrong arguments were passed to displayImage() method (ImageView reference must not be null)";
	private static final String ERROR_NOT_INIT = "ImageLoader must be init with configuration before using";
	private static final String ERROR_INIT_CONFIG_WITH_NULL = "ImageLoader configuration can not be initialized with null";

	private ImageLoaderConfiguration configuration;
	private ImageLoaderEngine engine;

	private ImageLoadingListener defaultListener = new SimpleImageLoadingListener();

	private volatile static ImageLoader instance;

	/** Returns singleton class instance */
	public static ImageLoader getInstance() {
		if (instance == null) {
			synchronized (ImageLoader.class) {
				if (instance == null) {
					instance = new ImageLoader();
				}
			}
		}
		return instance;
	}

	protected ImageLoader() {
	}

	/**
	 * Initializes ImageLoader instance with configuration.<br />
	 * If configurations was set before ( {@link #isInited()} == true) then this method does nothing.<br />
	 * To force initialization with new configuration you should {@linkplain #destroy() destroy ImageLoader} at first.
	 *
	 * @param configuration {@linkplain ImageLoaderConfiguration ImageLoader configuration}
	 * @throws IllegalArgumentException if <b>configuration</b> parameter is null
	 */
	public synchronized void init(ImageLoaderConfiguration configuration) {
		if (configuration == null) {
			throw new IllegalArgumentException(ERROR_INIT_CONFIG_WITH_NULL);
		}
		if (this.configuration == null) {
			L.d(LOG_INIT_CONFIG);
			// 装载 图片加载引擎 配置 也是 configuration来的
			engine = new ImageLoaderEngine(configuration);
			// 设置参数配置
			this.configuration = configuration;
		} else {
			L.w(WARNING_RE_INIT_CONFIG);
		}
	}

	/**
	 * Returns <b>true</b> - if ImageLoader {@linkplain #init(ImageLoaderConfiguration) is initialized with
	 * configuration}; <b>false</b> - otherwise
	 */
	public boolean isInited() {
		return configuration != null;
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn. <br/>
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware) {
		displayImage(uri, imageAware, null, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @param listener   {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                   UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, ImageLoadingListener listener) {
		displayImage(uri, imageAware, null, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @param options    {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                   decoding and displaying. If <b>null</b> - default display image options
	 *                   {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                   from configuration} will be used.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options) {
		displayImage(uri, imageAware, options, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @param options    {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                   decoding and displaying. If <b>null</b> - default display image options
	 *                   {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                   from configuration} will be used.
	 * @param listener   {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                   UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageLoadingListener listener) {
		displayImage(uri, imageAware, options, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri              Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware       {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                         which should display image
	 * @param options          {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                         decoding and displaying. If <b>null</b> - default display image options
	 *                         {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                         from configuration} will be used.
	 * @param listener         {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                         events on UI thread if this method is called on UI thread.
	 * @param progressListener {@linkplain com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener
	 *                         Listener} for image loading progress. Listener fires events on UI thread if this method
	 *                         is called on UI thread. Caching on disk should be enabled in
	 *                         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions options} to make
	 *                         this listener work.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		displayImage(uri, imageAware, options, null, listener, progressListener);
	}

	/**
	 *
	 * 显示图片的方法入口
	 *
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri              Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware       {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                         which should display image
	 *                         我们一般传过来的是 ImageView  可以找到相应的重载方法
	 *                         但是跟下去看 会发现 有一步是  new ImageViewAware(imageView) 处理了一下
	 *
	 * @param options          {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                         decoding and displaying. If <b>null</b> - default display image options
	 *                         {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                         from configuration} will be used.
	 * @param targetSize       {@linkplain ImageSize} Image target size. If <b>null</b> - size will depend on the view
	 *                         这个多数情况下 null 更具View 来定制
	 *
	 *
	 * @param listener         {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                         events on UI thread if this method is called on UI thread.
	 * @param progressListener {@linkplain com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener
	 *                         Listener} for image loading progress. Listener fires events on UI thread if this method
	 *                         is called on UI thread. Caching on disk should be enabled in
	 *                         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions options} to make
	 *                         this listener work.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageSize targetSize, ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		checkConfiguration();
		if (imageAware == null) {
			throw new IllegalArgumentException(ERROR_WRONG_ARGUMENTS);
		}
		if (listener == null) {
			// 有默认的监听器
			listener = defaultListener;
		}
		if (options == null) {
			// 默认的配置参数 TODO displayImage 默认的配置参数
			options = configuration.defaultDisplayImageOptions;
		}

		if (TextUtils.isEmpty(uri)) {
			// 如果 地址为空
			// 取消显示任务
			engine.cancelDisplayTaskFor(imageAware);
			// 回调监听器 开始加载图片
			listener.onLoadingStarted(uri, imageAware.getWrappedView());
			if (options.shouldShowImageForEmptyUri()) {
				// 判断配置参数走 是否有设置 空Url 显示图片
				imageAware.setImageDrawable(options.getImageForEmptyUri(configuration.resources));
			} else {
				// 如果没有 则设置 drawable为 null
				imageAware.setImageDrawable(null);
			}
			// 回调监听器 图片加载完成
			listener.onLoadingComplete(uri, imageAware.getWrappedView(), null);
			return;
		}

		// 如果 参数中的 大小为空,  多数情况下是为空
		if (targetSize == null) {
			// 通过ImageView 读取代销 同时 还给了 默认的最大的 宽高 是屏幕宽高
			//TODO 可以理解在 在使用 wrap_content 的时候 是使用的最大宽高?
			targetSize = ImageSizeUtils.defineTargetSizeForView(imageAware, configuration.getMaxImageSize());
		}
		// 生成一个 MemoryCache key  key 是更具 url 和 大小生成的
		String memoryCacheKey = MemoryCacheUtils.generateKey(uri, targetSize);
		// 加载引擎  把 ViewId 和 刚刚生成的 key 存入map 中
		engine.prepareDisplayTaskFor(imageAware, memoryCacheKey);
		// 回调 开始 加载图片
		listener.onLoadingStarted(uri, imageAware.getWrappedView());

		// 更具上面生成的 key 在memoryCache 中 查找 获取Bitmap
		Bitmap bmp = configuration.memoryCache.get(memoryCacheKey);
		if (bmp != null && !bmp.isRecycled()) {
			// 如果找到了 且没有被回收
			L.d(LOG_LOAD_IMAGE_FROM_MEMORY_CACHE, memoryCacheKey);

			if (options.shouldPostProcess()) {
				// 判断 是否需要对 图片进行处理
				// new 一个 ImageLoadingInfo 对象, 该类没有其他方法 就一个构造方法 而且是final  更多的是用来对于 信息的保存 成员变量类型都 default
				// engine.getLockForUri(uri) 是获取锁 不同于 Syn 的锁, 这种锁性能更好, 更有扩展性, 但是也要慎用
				ImageLoadingInfo imageLoadingInfo = new ImageLoadingInfo(uri, imageAware, targetSize, memoryCacheKey,
						options, listener, progressListener, engine.getLockForUri(uri));
				// new 一个 处理和显示图片的task
				ProcessAndDisplayImageTask displayTask = new ProcessAndDisplayImageTask(engine, bmp, imageLoadingInfo,
						defineHandler(options));
				if (options.isSyncLoading()) {
					// 这里 判断是不是 异步 更多的是 在加载图片 之前 是不是 就已经是 其他线程了 这样 就不需要在勇引擎的线程池做处理了
					//如果是 异步读取的话 默认是false
					displayTask.run();
				} else {
					// 把任务提交给引擎
					engine.submit(displayTask);
				}
			} else {
				// 如果不需再处理  图片了
				// 获取 getDisplayer 默认的是 SimpleBitmapDisplayer  还有些 渐隐渐现什么的
				options.getDisplayer().display(bmp, imageAware, LoadedFrom.MEMORY_CACHE);
				listener.onLoadingComplete(uri, imageAware.getWrappedView(), bmp);
			}
		} else {
			if (options.shouldShowImageOnLoading()) {
				// 判断是否 需要在loading的时候显示图片
				// 设置 loading 状态的图片
				imageAware.setImageDrawable(options.getImageOnLoading(configuration.resources));
			} else if (options.isResetViewBeforeLoading()) {
				// 如果需要 在读取图片执勤啊 reset View 那么 设置 Drawable为null
				imageAware.setImageDrawable(null);
			}

			// new 一个 图片信息
			ImageLoadingInfo imageLoadingInfo = new ImageLoadingInfo(uri, imageAware, targetSize, memoryCacheKey,
					options, listener, progressListener, engine.getLockForUri(uri));
			// new 一个 加载和显示图片的 任务
			// 一个没有 缓存的图片 加载入口在这里
			LoadAndDisplayImageTask displayTask = new LoadAndDisplayImageTask(engine, imageLoadingInfo,
					defineHandler(options));
			if (options.isSyncLoading()) {
				displayTask.run();
			} else {
				engine.submit(displayTask);
			}
		}
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn. <br/>
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView) {
		displayImage(uri, new ImageViewAware(imageView), null, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn. <br/>
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, ImageSize targetImageSize) {
		displayImage(uri, new ImageViewAware(imageView), null, targetImageSize, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @param options   {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                  decoding and displaying. If <b>null</b> - default display image options
	 *                  {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                  from configuration} will be used.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, DisplayImageOptions options) {
		displayImage(uri, new ImageViewAware(imageView), options, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @param listener  {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                  UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, ImageLoadingListener listener) {
		displayImage(uri, new ImageViewAware(imageView), null, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @param options   {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                  decoding and displaying. If <b>null</b> - default display image options
	 *                  {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                  from configuration} will be used.
	 * @param listener  {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                  UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, DisplayImageOptions options,
			ImageLoadingListener listener) {
		displayImage(uri, imageView, options, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri              Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView        {@link ImageView} which should display image
	 * @param options          {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                         decoding and displaying. If <b>null</b> - default display image options
	 *                         {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                         from configuration} will be used.
	 * @param listener         {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                         events on UI thread if this method is called on UI thread.
	 * @param progressListener {@linkplain com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener
	 *                         Listener} for image loading progress. Listener fires events on UI thread if this method
	 *                         is called on UI thread. Caching on disk should be enabled in
	 *                         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions options} to make
	 *                         this listener work.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		displayImage(uri, new ImageViewAware(imageView), options, listener, progressListener);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri      Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param listener {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on UI
	 *                 thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, ImageLoadingListener listener) {
		loadImage(uri, null, null, listener, null);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri             Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize Minimal size for {@link Bitmap} which will be returned in
	 *                        {@linkplain ImageLoadingListener#onLoadingComplete(String, android.view.View,
	 *                        android.graphics.Bitmap)} callback}. Downloaded image will be decoded
	 *                        and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                        larger) than incoming targetImageSize.
	 * @param listener        {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                        events on UI thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, ImageSize targetImageSize, ImageLoadingListener listener) {
		loadImage(uri, targetImageSize, null, listener, null);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri      Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param options  {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                 decoding and displaying. If <b>null</b> - default display image options
	 *                 {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 *                 configuration} will be used.<br />
	 * @param listener {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on UI
	 *                 thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, DisplayImageOptions options, ImageLoadingListener listener) {
		loadImage(uri, null, options, listener, null);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri             Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize Minimal size for {@link Bitmap} which will be returned in
	 *                        {@linkplain ImageLoadingListener#onLoadingComplete(String, android.view.View,
	 *                        android.graphics.Bitmap)} callback}. Downloaded image will be decoded
	 *                        and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                        larger) than incoming targetImageSize.
	 * @param options         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                        decoding and displaying. If <b>null</b> - default display image options
	 *                        {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                        from configuration} will be used.<br />
	 * @param listener        {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                        events on UI thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, ImageSize targetImageSize, DisplayImageOptions options,
			ImageLoadingListener listener) {
		loadImage(uri, targetImageSize, options, listener, null);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * 加载 一张图片 并放入缓存
	 *
	 * 在 listener 的完成回调中 会返回相应的bitmap
	 *
	 * @param uri              Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize  Minimal size for {@link Bitmap} which will be returned in
	 *                         {@linkplain ImageLoadingListener#onLoadingComplete(String, android.view.View,
	 *                         android.graphics.Bitmap)} callback}. Downloaded image will be decoded
	 *                         and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                         larger) than incoming targetImageSize.
	 * @param options          {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                         decoding and displaying. If <b>null</b> - default display image options
	 *                         {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                         from configuration} will be used.<br />
	 * @param listener         {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                         events on UI thread if this method is called on UI thread.
	 * @param progressListener {@linkplain com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener
	 *                         Listener} for image loading progress. Listener fires events on UI thread if this method
	 *                         is called on UI thread. Caching on disk should be enabled in
	 *                         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions options} to make
	 *                         this listener work.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, ImageSize targetImageSize, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		checkConfiguration();
		if (targetImageSize == null) {
			targetImageSize = configuration.getMaxImageSize();
		}
		if (options == null) {
			options = configuration.defaultDisplayImageOptions;
		}
		// 其实就是去显示 图片 不过 imageAware 是一个 NonViewAware 然后图片大小如果没有设置的话 就是默认的最大
		NonViewAware imageAware = new NonViewAware(uri, targetImageSize, ViewScaleType.CROP);
		displayImage(uri, imageAware, options, listener, progressListener);
	}

	/**
	 * Loads and decodes image synchronously.<br />
	 * Default display image options
	 * {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @return Result image Bitmap. Can be <b>null</b> if image loading/decoding was failed or cancelled.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public Bitmap loadImageSync(String uri) {
		return loadImageSync(uri, null, null);
	}

	/**
	 * Loads and decodes image synchronously.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri     Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param options {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                decoding and scaling. If <b>null</b> - default display image options
	 *                {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 *                configuration} will be used.
	 * @return Result image Bitmap. Can be <b>null</b> if image loading/decoding was failed or cancelled.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public Bitmap loadImageSync(String uri, DisplayImageOptions options) {
		return loadImageSync(uri, null, options);
	}

	/**
	 * Loads and decodes image synchronously.<br />
	 * Default display image options
	 * {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri             Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize Minimal size for {@link Bitmap} which will be returned. Downloaded image will be decoded
	 *                        and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                        larger) than incoming targetImageSize.
	 * @return Result image Bitmap. Can be <b>null</b> if image loading/decoding was failed or cancelled.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public Bitmap loadImageSync(String uri, ImageSize targetImageSize) {
		return loadImageSync(uri, targetImageSize, null);
	}

	/**
	 * Loads and decodes image synchronously.<br />
	 *
	 * 同步 读取图片 , 直接读取不同, 不把 图片加载的 任务房屋 加载引擎中,
	 * 就直接在当前的线程 线性走下来,  可以直接返回 Bitmap
	 * 注意  这一步 是耗时操作
	 *
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri             Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize Minimal size for {@link Bitmap} which will be returned. Downloaded image will be decoded
	 *                        and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                        larger) than incoming targetImageSize.
	 * @param options         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                        decoding and scaling. If <b>null</b> - default display image options
	 *                        {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                        from configuration} will be used.
	 * @return Result image Bitmap. Can be <b>null</b> if image loading/decoding was failed or cancelled.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public Bitmap loadImageSync(String uri, ImageSize targetImageSize, DisplayImageOptions options) {
		if (options == null) {
			options = configuration.defaultDisplayImageOptions;
		}
		// 这里 会更具传进来的 options 在处理一下 主要就是 重新设置 一下  syncLoading(true)
		options = new DisplayImageOptions.Builder().cloneFrom(options).syncLoading(true).build();

		// new 一个 SyncImageLoadingListener
		SyncImageLoadingListener listener = new SyncImageLoadingListener();
		// 然后就是不普通的读取 图片, 不过这里设置 了 同步读取 , 那么 在loadImage displayImage 放纵就不会把任务放到 加载引擎中 而是直接执行
		// 对了这样 确实就可以 实时的 获取 Bitmap 了 就不需要通过回调了
		loadImage(uri, targetImageSize, options, listener);
		return listener.getLoadedBitmap();
	}

	/**
	 * Checks if ImageLoader's configuration was initialized
	 *	检查 configuration 配置
	 * @throws IllegalStateException if configuration wasn't initialized
	 */
	private void checkConfiguration() {
		if (configuration == null) {
			throw new IllegalStateException(ERROR_NOT_INIT);
		}
	}

	/** Sets a default loading listener for all display and loading tasks. */
	public void setDefaultLoadingListener(ImageLoadingListener listener) {
		defaultListener = listener == null ? new SimpleImageLoadingListener() : listener;
	}

	/**
	 * Returns memory cache
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public MemoryCache getMemoryCache() {
		checkConfiguration();
		return configuration.memoryCache;
	}

	/**
	 * Clears memory cache
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void clearMemoryCache() {
		checkConfiguration();
		configuration.memoryCache.clear();
	}

	/**
	 * Returns disk cache
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @deprecated Use {@link #getDiskCache()} instead
	 */
	@Deprecated
	public DiskCache getDiscCache() {
		return getDiskCache();
	}

	/**
	 * Returns disk cache
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public DiskCache getDiskCache() {
		checkConfiguration();
		return configuration.diskCache;
	}

	/**
	 * Clears disk cache.
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @deprecated Use {@link #clearDiskCache()} instead
	 */
	@Deprecated
	public void clearDiscCache() {
		clearDiskCache();
	}

	/**
	 * Clears disk cache.
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void clearDiskCache() {
		checkConfiguration();
		configuration.diskCache.clear();
	}

	/**
	 * Returns URI of image which is loading at this moment into passed
	 * {@link com.nostra13.universalimageloader.core.imageaware.ImageAware ImageAware}
	 *
	 * 获取 包装 View 对应的 Uri
	 */
	public String getLoadingUriForView(ImageAware imageAware) {
		return engine.getLoadingUriForView(imageAware);
	}

	/**
	 * Returns URI of image which is loading at this moment into passed
	 * {@link android.widget.ImageView ImageView}
	 */
	public String getLoadingUriForView(ImageView imageView) {
		return engine.getLoadingUriForView(new ImageViewAware(imageView));
	}

	/**
	 * Cancel the task of loading and displaying image for passed
	 * {@link com.nostra13.universalimageloader.core.imageaware.ImageAware ImageAware}.
	 *
	 * 取消 当前 View 的读取 图片的任务
	 *
	 * @param imageAware {@link com.nostra13.universalimageloader.core.imageaware.ImageAware ImageAware} for
	 *                   which display task will be cancelled
	 */
	public void cancelDisplayTask(ImageAware imageAware) {
		engine.cancelDisplayTaskFor(imageAware);
	}

	/**
	 * Cancel the task of loading and displaying image for passed
	 * {@link android.widget.ImageView ImageView}.
	 *
	 * @param imageView {@link android.widget.ImageView ImageView} for which display task will be cancelled
	 */
	public void cancelDisplayTask(ImageView imageView) {
		engine.cancelDisplayTaskFor(new ImageViewAware(imageView));
	}

	/**
	 * Denies or allows ImageLoader to download images from the network.<br />
	 * <br />
	 * If downloads are denied and if image isn't cached then
	 * {@link ImageLoadingListener#onLoadingFailed(String, View, FailReason)} callback will be fired with
	 * {@link FailReason.FailType#NETWORK_DENIED}
	 *
	 * 拒绝 或 运行 ImageLoader 能否从网络上下载图片
	 *
	 * @param denyNetworkDownloads pass <b>true</b> - to deny engine to download images from the network; <b>false</b> -
	 *                             to allow engine to download images from network.
	 */
	public void denyNetworkDownloads(boolean denyNetworkDownloads) {
		engine.denyNetworkDownloads(denyNetworkDownloads);
	}

	/**
	 * Sets option whether ImageLoader will use {@link FlushedInputStream} for network downloads to handle <a
	 * href="http://code.google.com/p/android/issues/detail?id=6066">this known problem</a> or not.
	 *
	 * 是否是 慢速网络 如果设置的是 处理慢速网络的是 那么 下载数据 就会使用 FlushedInputStream
	 *
	 * @param handleSlowNetwork pass <b>true</b> - to use {@link FlushedInputStream} for network downloads; <b>false</b>
	 *                          - otherwise.
	 *
	 *
	 */
	public void handleSlowNetwork(boolean handleSlowNetwork) {
		engine.handleSlowNetwork(handleSlowNetwork);
	}

	/**
	 * Pause ImageLoader. All new "load&display" tasks won't be executed until ImageLoader is {@link #resume() resumed}.
	 * <br />
	 * Already running tasks are not paused.
	 */
	public void pause() {
		engine.pause();
	}

	/** Resumes waiting "load&display" tasks */
	public void resume() {
		engine.resume();
	}

	/**
	 * Cancels all running and scheduled display image tasks.<br />
	 * <b>NOTE:</b> This method doesn't shutdown
	 * {@linkplain com.nostra13.universalimageloader.core.ImageLoaderConfiguration.Builder#taskExecutor(java.util.concurrent.Executor)
	 * custom task executors} if you set them.<br />
	 * ImageLoader still can be used after calling this method.
	 */
	public void stop() {
		engine.stop();
	}

	/**
	 * {@linkplain #stop() Stops ImageLoader} and clears current configuration. <br />
	 * You can {@linkplain #init(ImageLoaderConfiguration) init} ImageLoader with new configuration after calling this
	 * method.
	 */
	public void destroy() {
		if (configuration != null) L.d(LOG_DESTROY);
		stop();
		configuration.diskCache.close();
		engine = null;
		configuration = null;
	}

	/**
	 * 获取Handle
	 * @param options
	 * @return
	 */
	private static Handler defineHandler(DisplayImageOptions options) {
		// 获取 options 中的Handler 多少情况为空
		Handler handler = options.getHandler();
		if (options.isSyncLoading()) {
			// 如果是 同步读取的不需要 handler 通知其他线程
			handler = null;
		} else if (handler == null && Looper.myLooper() == Looper.getMainLooper()) {
			// 如果当期那是在 主线程的话 那么 需要 自己 new一个Handle
			handler = new Handler();
		}
		return handler;
	}

	/**
	 * Listener which is designed for synchronous image loading.
	 *
	 * 同步读取图片 Listener
	 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
	 * @since 1.9.0
	 */
	private static class SyncImageLoadingListener extends SimpleImageLoadingListener {

		private Bitmap loadedImage;

		@Override
		public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
			this.loadedImage = loadedImage;
		}

		public Bitmap getLoadedBitmap() {
			return loadedImage;
		}
	}
}
