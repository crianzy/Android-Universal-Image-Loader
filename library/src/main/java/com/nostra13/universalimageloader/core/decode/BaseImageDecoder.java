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
package com.nostra13.universalimageloader.core.decode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Matrix;
import android.media.ExifInterface;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.download.ImageDownloader.Scheme;
import com.nostra13.universalimageloader.utils.ImageSizeUtils;
import com.nostra13.universalimageloader.utils.IoUtils;
import com.nostra13.universalimageloader.utils.L;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes images to {@link Bitmap}, scales them to needed size
 *
 * 这里类 主要是 文件流 转化成 Bitmap  并处理好 缩放 预计旋转方向
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ImageDecodingInfo
 * @since 1.8.3
 */
public class BaseImageDecoder implements ImageDecoder {

	protected static final String LOG_SUBSAMPLE_IMAGE = "Subsample original image (%1$s) to %2$s (scale = %3$d) [%4$s]";
	protected static final String LOG_SCALE_IMAGE = "Scale subsampled image (%1$s) to %2$s (scale = %3$.5f) [%4$s]";
	protected static final String LOG_ROTATE_IMAGE = "Rotate image on %1$d\u00B0 [%2$s]";
	protected static final String LOG_FLIP_IMAGE = "Flip image horizontally [%s]";
	protected static final String ERROR_NO_IMAGE_STREAM = "No stream for image [%s]";
	protected static final String ERROR_CANT_DECODE_IMAGE = "Image can't be decoded [%s]";

	protected final boolean loggingEnabled;

	/**
	 * @param loggingEnabled Whether debug logs will be written to LogCat. Usually should match {@link
	 *                       com.nostra13.universalimageloader.core.ImageLoaderConfiguration.Builder#writeDebugLogs()
	 *                       ImageLoaderConfiguration.writeDebugLogs()}
	 */
	public BaseImageDecoder(boolean loggingEnabled) {
		this.loggingEnabled = loggingEnabled;
	}

	/**
	 * Decodes image from URI into {@link Bitmap}. Image is scaled close to incoming {@linkplain ImageSize target size}
	 * during decoding (depend on incoming parameters).
	 * 更具 图片信息 编码处 图片
	 *
	 * @param decodingInfo Needed data for decoding image
	 * @return Decoded bitmap
	 * @throws IOException                   if some I/O exception occurs during image reading
	 * @throws UnsupportedOperationException if image URI has unsupported scheme(protocol)
	 */
	@Override
	public Bitmap decode(ImageDecodingInfo decodingInfo) throws IOException {
		Bitmap decodedBitmap;
		ImageFileInfo imageInfo;

		InputStream imageStream = getImageStream(decodingInfo);
		if (imageStream == null) {
			// 出错 返回null 外面会处理
			L.e(ERROR_NO_IMAGE_STREAM, decodingInfo.getImageKey());
			return null;
		}
		try {
			// 获取 图片 宽高  exif 信息
			imageInfo = defineImageSizeAndRotation(imageStream, decodingInfo);
			// 前面把流 读了一遍 这里需要重置
			imageStream = resetStream(imageStream, decodingInfo);
			// 获取表面皿参数 主要设置 缩放带下
			Options decodingOptions = prepareDecodingOptions(imageInfo.imageSize, decodingInfo);
			// 编码 bitmap
			decodedBitmap = BitmapFactory.decodeStream(imageStream, null, decodingOptions);
		} finally {
			//关闭流
			IoUtils.closeSilently(imageStream);
		}

		if (decodedBitmap == null) {
			// 出错了 提示
			L.e(ERROR_CANT_DECODE_IMAGE, decodingInfo.getImageKey());
		} else {
			// 更护方向 在处理一遍
			decodedBitmap = considerExactScaleAndOrientatiton(decodedBitmap, decodingInfo, imageInfo.exif.rotation,
					imageInfo.exif.flipHorizontal);
		}
		return decodedBitmap;
	}

	protected InputStream getImageStream(ImageDecodingInfo decodingInfo) throws IOException {
		// 这里调用  decodingInfo.getExtraForDownloader() 里面存放了一下 下载图片需要的参数
		// 从下载器中 更具 uri 获取 InputStream 流
		// 这里 传过来的uri 多数情况下 不是 http 这些uri 而是 file:/// 这些
		return decodingInfo.getDownloader().getStream(decodingInfo.getImageUri(), decodingInfo.getExtraForDownloader());
	}

	/**
	 * 读取 Bitmap 的宽高信息 并 注入到 ImageFileInfo 对象中
	 *
	 * @param imageStream
	 * @param decodingInfo
	 * @return
	 * @throws IOException
	 */
	protected ImageFileInfo defineImageSizeAndRotation(InputStream imageStream, ImageDecodingInfo decodingInfo)
			throws IOException {
		Options options = new Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeStream(imageStream, null, options);

		ExifInfo exif;
		String imageUri = decodingInfo.getImageUri();
		if (decodingInfo.shouldConsiderExifParams() && canDefineExifParams(imageUri, options.outMimeType)) {
			// 获取 exif 信息
			exif = defineExifOrientation(imageUri);
		} else {
			exif = new ExifInfo();
		}
		return new ImageFileInfo(new ImageSize(options.outWidth, options.outHeight, exif.rotation), exif);
	}

	private boolean canDefineExifParams(String imageUri, String mimeType) {
		return "image/jpeg".equalsIgnoreCase(mimeType) && (Scheme.ofUri(imageUri) == Scheme.FILE);
	}


	/**
	 * 去顶 exif 信息
	 * @param imageUri
	 * @return
	 */
	protected ExifInfo defineExifOrientation(String imageUri) {
		int rotation = 0;
		boolean flip = false;
		try {
			// 从文件中读取 exif 信息
			ExifInterface exif = new ExifInterface(Scheme.FILE.crop(imageUri));
			int exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
			switch (exifOrientation) {
				case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
					flip = true;
				case ExifInterface.ORIENTATION_NORMAL:
					rotation = 0;
					break;
				case ExifInterface.ORIENTATION_TRANSVERSE:
					flip = true;
				case ExifInterface.ORIENTATION_ROTATE_90:
					rotation = 90;
					break;
				case ExifInterface.ORIENTATION_FLIP_VERTICAL:
					flip = true;
				case ExifInterface.ORIENTATION_ROTATE_180:
					rotation = 180;
					break;
				case ExifInterface.ORIENTATION_TRANSPOSE:
					flip = true;
				case ExifInterface.ORIENTATION_ROTATE_270:
					rotation = 270;
					break;
			}
		} catch (IOException e) {
			L.w("Can't read EXIF tags from file [%s]", imageUri);
		}
		return new ExifInfo(rotation, flip);
	}

	/**
	 * 准备 Decode 相关参数
	 * 设置 缩放倍数
	 * @param imageSize
	 * @param decodingInfo
	 * @return
	 */
	protected Options prepareDecodingOptions(ImageSize imageSize, ImageDecodingInfo decodingInfo) {
		ImageScaleType scaleType = decodingInfo.getImageScaleType();
		int scale;
		if (scaleType == ImageScaleType.NONE) {
			// 不缩放
			scale = 1;
		} else if (scaleType == ImageScaleType.NONE_SAFE) {
			// 所望类型为不安全  分辨率 太大了
			// 计算 最小缩放倍数 这里缩小到 小于 最大的纹理分辨率
			scale = ImageSizeUtils.computeMinImageSampleSize(imageSize);
		} else {
			ImageSize targetSize = decodingInfo.getTargetSize();
			// 是否是 缩小 两倍的的类型
			boolean powerOf2 = scaleType == ImageScaleType.IN_SAMPLE_POWER_OF_2;
			// 计算缩小倍数 还会更具 ViewScaleType 来确定
			scale = ImageSizeUtils.computeImageSampleSize(imageSize, targetSize, decodingInfo.getViewScaleType(), powerOf2);
		}
		if (scale > 1 && loggingEnabled) {
			// 所限 错放比例 >1  表示要放大 提示一下
			L.d(LOG_SUBSAMPLE_IMAGE, imageSize, imageSize.scaleDown(scale), scale, decodingInfo.getImageKey());
		}

		// 获取编码参数
		Options decodingOptions = decodingInfo.getDecodingOptions();
		// 设置缩放大小
		decodingOptions.inSampleSize = scale;
		// 返回 编码参数
		return decodingOptions;
	}

	protected InputStream resetStream(InputStream imageStream, ImageDecodingInfo decodingInfo) throws IOException {
		if (imageStream.markSupported()) {
			// 如果支持 标记的
			try {
				// reset 输入流
				imageStream.reset();
				return imageStream;
			} catch (IOException ignored) {
			}
		}
		// 否则 关闭输入流
		IoUtils.closeSilently(imageStream);
		// 重新过去输入流
		return getImageStream(decodingInfo);
	}

	/**
	 * 再次确定 图片 缩放 或方向
	 * @param subsampledBitmap
	 * @param decodingInfo
	 * @param rotation
	 * @param flipHorizontal
	 * @return
	 */
	protected Bitmap considerExactScaleAndOrientatiton(Bitmap subsampledBitmap, ImageDecodingInfo decodingInfo,
			int rotation, boolean flipHorizontal) {
		Matrix m = new Matrix();
		// Scale to exact size if need
		ImageScaleType scaleType = decodingInfo.getImageScaleType();
		if (scaleType == ImageScaleType.EXACTLY || scaleType == ImageScaleType.EXACTLY_STRETCHED) {
			// 这里 更具方向 重新 生成 ImageSize 可能 较之前 宽高 反过来了
			ImageSize srcSize = new ImageSize(subsampledBitmap.getWidth(), subsampledBitmap.getHeight(), rotation);
			// 计算缩放 比例
			float scale = ImageSizeUtils.computeImageScale(srcSize, decodingInfo.getTargetSize(), decodingInfo
					.getViewScaleType(), scaleType == ImageScaleType.EXACTLY_STRETCHED);
			if (Float.compare(scale, 1f) != 0) {
				m.setScale(scale, scale);

				if (loggingEnabled) {
					L.d(LOG_SCALE_IMAGE, srcSize, srcSize.scale(scale), scale, decodingInfo.getImageKey());
				}
			}
		}
		// 处理旋转
		// Flip bitmap if need
		if (flipHorizontal) {
			m.postScale(-1, 1);

			if (loggingEnabled) L.d(LOG_FLIP_IMAGE, decodingInfo.getImageKey());
		}
		// Rotate bitmap if need
		if (rotation != 0) {
			m.postRotate(rotation);

			if (loggingEnabled) L.d(LOG_ROTATE_IMAGE, rotation, decodingInfo.getImageKey());
		}

		// 更具  Matrix 重新生成 Bitmap
		Bitmap finalBitmap = Bitmap.createBitmap(subsampledBitmap, 0, 0, subsampledBitmap.getWidth(), subsampledBitmap
				.getHeight(), m, true);
		// 这里要注意 createBitmap 不一定 创建的是 新的Bitmap 如果 m 没有任何变化的 那么 还是返回原来的Bitmap
		if (finalBitmap != subsampledBitmap) {
			subsampledBitmap.recycle();
		}
		return finalBitmap;
	}

	protected static class ExifInfo {

		public final int rotation;
		public final boolean flipHorizontal;

		protected ExifInfo() {
			this.rotation = 0;
			this.flipHorizontal = false;
		}

		protected ExifInfo(int rotation, boolean flipHorizontal) {
			this.rotation = rotation;
			this.flipHorizontal = flipHorizontal;
		}
	}

	/**
	 * 图片的 宽高 exif 信息
	 */
	protected static class ImageFileInfo {

		public final ImageSize imageSize;
		public final ExifInfo exif;

		protected ImageFileInfo(ImageSize imageSize, ExifInfo exif) {
			this.imageSize = imageSize;
			this.exif = exif;
		}
	}
}
