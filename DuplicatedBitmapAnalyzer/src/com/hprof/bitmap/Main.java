package com.hprof.bitmap;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Heap;
import com.squareup.haha.perflib.HprofParser;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.io.HprofBuffer;
import com.squareup.haha.perflib.io.MemoryMappedFileBuffer;
import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.AnalyzerProgressListener;
import com.squareup.leakcanary.ExcludedRefs;
import com.squareup.leakcanary.HeapAnalyzer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import sun.misc.BASE64Encoder;

public class Main {

    /**
     * Bitmap重复计数器
     */
    private static Map<String, ReportBean> counter = new HashMap<>();

    public static void main(String[] args) {

        // 堆栈路径
        String dumpFilePath = "//Users//quchao//Documents//heapdump//memory-44.hprof";

        try {
            counter.clear();
            System.out.println("the program is running, please wait ........");
            analyzerDump(dumpFilePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void analyzerDump(String dumpfilePath) throws Exception {
        File heapDumpFile = new File(dumpfilePath);
        HprofBuffer hprofBuffer = new MemoryMappedFileBuffer(heapDumpFile);
        HprofParser parser = new HprofParser(hprofBuffer);
        Snapshot snapshot = parser.parse();

        String className = "android.graphics.Bitmap";
        ClassObj bitmapClass = snapshot.findClass(className);

        List<Instance> bitmapInstances = bitmapClass.getInstancesList();

        for (Instance instance : bitmapInstances) {
            Heap heap1 = instance.getHeap();
            String heap1Name = heap1.getName();

            if (!heap1Name.equalsIgnoreCase("app")) {
                continue;
            }

            ArrayInstance buffer = HahaHelper.fieldValue(((ClassInstance) instance).getValues(), "mBuffer");
            Integer height = HahaHelper.fieldValue(((ClassInstance) instance).getValues(), "mHeight");
            Integer width = HahaHelper.fieldValue(((ClassInstance) instance).getValues(), "mWidth");

            Class<?> personType = buffer.getClass();
            Method method = personType.getDeclaredMethod("asRawByteArray", int.class, int.class);
            method.setAccessible(true);
            byte[] data = (byte[]) method.invoke(buffer, 0, buffer.getValues().length);

            BASE64Encoder base64Encoder = new BASE64Encoder();
            String str = base64Encoder.encode(data);
            String hash = md5(str);

            // 不存在，则加入
            if (!counter.keySet().contains(hash)) {
                ReportBean reportBean = new ReportBean();
                reportBean.setBufferHash(hash);
                reportBean.setHeight(height);
                reportBean.setWidth(width);
                reportBean.setBufferSize(buffer.getSize());
                reportBean.setDuplcateCount(0);
                reportBean.setStacks(printStack1(instance, snapshot));
                counter.put(hash, reportBean);

            } else {
                // 存在
                ReportBean reportBean = counter.get(hash);
                reportBean.setDuplcateCount(reportBean.getDuplcateCount() + 1);

                // 保存图片
                String pngFilePathDir = heapDumpFile.getParent()
                        + File.separator + "images";
                File file = new File(pngFilePathDir);
                if (!file.exists()){
                    file.mkdir();
                }
                String pngFilePath = pngFilePathDir+ File.separator+ hash + ".png";

                Argb8888BitmapExtractor.getImage(width, height, data, pngFilePath);
            }
        }

        List<ReportBean> reportBeanList = new ArrayList<>();

        for (ReportBean reportBean : counter.values()) {
            if (reportBean.getDuplcateCount() > 0) {
                reportBeanList.add(reportBean);
            }
        }

        Collections.sort(reportBeanList, (reportBean, t1) -> t1.getDuplcateCount() - reportBean.getDuplcateCount() > 0 ? 1 : -1);

        for (int i = 0; i < reportBeanList.size(); i++) {
            ReportBean reportBean = reportBeanList.get(i);
            System.out.println(reportBean.toString());
        }
    }

    /**
     * 获取 Instance 的 stack
     *
     * @param instance 对象实例
     * @param snapshot 快照
     * @return 堆栈信息
     */
    private static String printStack1(Instance instance, Snapshot snapshot) {
        String stacks = "";

        ExcludedRefs excludedRefs = ExcludedRefs.builder().build();
        HeapAnalyzer heapAnalyzer = new HeapAnalyzer(excludedRefs, AnalyzerProgressListener.NONE,
                Collections.emptyList());

        Class<?> heapAnalyzerClass = heapAnalyzer.getClass();

        try {
            Method method = heapAnalyzerClass.getDeclaredMethod("findLeakTrace",
                    long.class,
                    Snapshot.class,
                    Instance.class,
                    boolean.class);

            method.setAccessible(true);

            long analysisStartNanoTime = System.nanoTime();

            AnalysisResult analysisResult = (AnalysisResult) method.invoke(heapAnalyzer,
                    analysisStartNanoTime,
                    snapshot,
                    instance,
                    false);

            String string = null;
            if (analysisResult.leakTrace != null) {
                string = analysisResult.leakTrace.toString();
            }
            stacks = string;
        } catch (Exception e) {
            System.out.println("Exception =" + e.getMessage());
        }

        return stacks;
    }

    /**
     * 计算 md5
     *
     * @param string 编码后的图片数组
     * @return md5加密后的图片字符串
     */
    private static String md5(String string) {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
            byte[] bytes = md5.digest(string.getBytes());
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                String temp = Integer.toHexString(b & 0xff);
                if (temp.length() == 1) {
                    temp = "0" + temp;
                }
                result.append(temp);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 根据 byte[] 保存图片到文件
     * 参考
     * https://github.com/JetBrains/adt-tools-base/blob/master/ddmlib/src/main/java/com/android/ddmlib/BitmapDecoder.java
     */
    private static class Argb8888BitmapExtractor {

        static void getImage(int width, int height, byte[] rgba, String pngFilePath) throws IOException {
            BufferedImage bufferedImage = new BufferedImage(width, height,
                    BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < height; y++) {
                int stride = y * width;
                for (int x = 0; x < width; x++) {
                    int i = (stride + x) * 4;
                    long rgb = 0;
                    rgb |= ((long) rgba[i] & 0xff) << 16; // r
                    rgb |= ((long) rgba[i + 1] & 0xff) << 8;  // g
                    rgb |= ((long) rgba[i + 2] & 0xff);       // b
                    rgb |= ((long) rgba[i + 3] & 0xff) << 24; // a
                    bufferedImage.setRGB(x, y, (int) (rgb & 0xffffffffL));
                }
            }
            File outPutFile = new File(pngFilePath);
            ImageIO.write(bufferedImage, "png", outPutFile);
        }
    }
}
