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
package com.nostra13.universalimageloader.core.assist;

/**
 * Type of image scaling during decoding.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.5.0
 */
public enum ImageScaleType {
	/** Image won't be scaled */
	NONE,
	/**
	 * Image will be scaled down only if image size is greater than
	 * {@linkplain javax.microedition.khronos.opengles.GL10#GL_MAX_TEXTURE_SIZE maximum acceptable texture size}.
	 * Usually it's 2048x2048.<br />
	 * If Bitmap is expected to display than it must not exceed this size (otherwise you'll get the exception
	 * "OpenGLRenderer: Bitmap too large to be uploaded into a texture".<br />
	 * Image will be subsampled in an integer number of times (1, 2, 3, ...) to maximum texture size of device.
	 *
	 * 在底层opengl 上纹理 显示的最大分辨率是  2048x2048
	 * 如果超过这个 大小 会报出说  Bitmap too large to be uploaded into a texture
	 *
	 * 图片将会整数倍的缩小 知道 小于 最大值
	 *
	 */
	NONE_SAFE,
	/**
	 * Image will be reduces 2-fold until next reduce step make image smaller target size.<br />
	 * It's <b>fast</b> type and it's preferable for usage in lists/grids/galleries (and other
	 * {@linkplain android.widget.AdapterView adapter-views}) .<br />
	 * Relates to {@link android.graphics.BitmapFactory.Options#inSampleSize}<br />
	 * Note: If original image size is smaller than target size then original image <b>won't</b> be scaled.
	 *
	 * 图片每次缩小2 被 直到 图片小于 target size
	 * 很适合用于 显示 ListView GridView 中
	 * 如果图片本少 小于 target 那么就不会缩放
	 *
	 */
	IN_SAMPLE_POWER_OF_2,
	/**
	 * Image will be subsampled in an integer number of times (1, 2, 3, ...). Use it if memory economy is quite
	 * important.<br />
	 * Relates to {@link android.graphics.BitmapFactory.Options#inSampleSize}<br />
	 * Note: If original image size is smaller than target size then original image <b>won't</b> be scaled.
	 */
	IN_SAMPLE_INT,
	/**
	 * Image will scaled-down exactly to target size (scaled width or height or both will be equal to target size;
	 * depends on {@linkplain android.widget.ImageView.ScaleType ImageView's scale type}). Use it if memory economy is
	 * critically important.<br />
	 * <b>Note:</b> If original image size is smaller than target size then original image <b>won't</b> be scaled.<br />
	 * <br />
	 * <b>NOTE:</b> For creating result Bitmap (of exact size) additional Bitmap will be created with
	 * {@link android.graphics.Bitmap#createBitmap(android.graphics.Bitmap, int, int, int, int, android.graphics.Matrix, boolean)
	 * Bitmap.createBitmap(...)}.<br />
	 * <b>Cons:</b> Saves memory by keeping smaller Bitmap in memory cache (comparing with IN_SAMPLE... scale types)<br />
	 * <b>Pros:</b> Requires more memory in one time for creation of result Bitmap.
	 *
	 * 缩放到恰好的  target size  在内存不足的情况下 用 挺好的
	 *
	 * 优点 存在缓存中图片较小
	 * 缺点 就是需要 多一次 床架 Bitmap
	 */
	EXACTLY,
	/**
	 * Image will scaled exactly to target size (scaled width or height or both will be equal to target size; depends on
	 * {@linkplain android.widget.ImageView.ScaleType ImageView's scale type}). Use it if memory economy is critically
	 * important.<br />
	 * <b>Note:</b> If original image size is smaller than target size then original image <b>will be stretched</b> to
	 * target size.<br />
	 * <br />
	 * <b>NOTE:</b> For creating result Bitmap (of exact size) additional Bitmap will be created with
	 * {@link android.graphics.Bitmap#createBitmap(android.graphics.Bitmap, int, int, int, int, android.graphics.Matrix, boolean)
	 * Bitmap.createBitmap(...)}.<br />
	 * <b>Cons:</b> Saves memory by keeping smaller Bitmap in memory cache (comparing with IN_SAMPLE... scale types)<br />
	 * <b>Pros:</b> Requires more memory in one time for creation of result Bitmap.
	 *
	 * srcSize(10x10), targetSize(20x20), stretch = true  -> scale = 2
	 *
	 * 宽高 缩放到 和 target 一致
	 * 即使 图片大小 小于 target
	 *
	 *
	 */
	EXACTLY_STRETCHED
}
