/**
 * 色散效果 JNI 接口 (Native Chromatic Dispersion)
 *
 * 提供 Kotlin 到 C++ 的桥接接口
 */
package com.example.liquidglass

import android.graphics.Bitmap

/**
 * 色散效果原生接口
 */
object NativeChromaticDispersion {

    init {
        System.loadLibrary("nativegauss")
    }

    /**
     * 应用色散效果（原位处理）
     *
     * @param source 源图像
     * @param edgeDistance 边缘距离贴图
     * @param normalMap 法线贴图（可选，传 null 使用径向法线）
     * @param result 结果图像
     * @param refThickness 折射厚度
     * @param refFactor 折射系数
     * @param refDispersion 色散增益
     * @param dpr 设备像素比
     * @param useBilinear 是否使用双线性插值
     */
    external fun chromaticDispersionInplace(
        source: Bitmap,
        edgeDistance: Bitmap,
        normalMap: Bitmap?,
        result: Bitmap,
        refThickness: Float,
        refFactor: Float,
        refDispersion: Float,
        dpr: Float,
        useBilinear: Boolean
    )

    /**
     * 便捷方法：应用色散效果并返回新 Bitmap
     *
     * @param source 源图像
     * @param edgeDistance 边缘距离贴图
     * @param normalMap 法线贴图（可选）
     * @param refThickness 折射厚度
     * @param refFactor 折射系数
     * @param refDispersion 色散增益
     * @param dpr 设备像素比
     * @param useBilinear 是否使用双线性插值
     * @return 应用色散后的新图像
     */
    fun apply(
        source: Bitmap,
        edgeDistance: Bitmap,
        normalMap: Bitmap? = null,
        refThickness: Float = 100f,
        refFactor: Float = 1.5f,
        refDispersion: Float = 7f,
        dpr: Float = 1.0f,
        useBilinear: Boolean = true
    ): Bitmap {
        // 创建结果 Bitmap
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)

        // 调用原生方法
        chromaticDispersionInplace(
            source,
            edgeDistance,
            normalMap,
            result,
            refThickness,
            refFactor,
            refDispersion,
            dpr,
            useBilinear
        )

        return result
    }
}

