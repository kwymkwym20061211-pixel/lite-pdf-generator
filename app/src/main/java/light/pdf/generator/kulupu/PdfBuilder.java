package light.pdf.generator.kulupu;


import android.content.Context;
import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class PdfBuilder{
    
    static{
        System.loadLibrary("pdf_builder");
    }
    
    public static final int CHANNELS_GRAY = 1;
    public static final int CHANNELS_RGB = 3;
    
    public static final int BPC_1 = 1;
    public static final int BPC_2 = 2;
    public static final int BPC_4 = 4;
    public static final int BPC_8 = 8;
    
    public static final int ERR_OK = 0;
    public static final int ERR_ALLOC = -1;
    public static final int ERR_COMPRESS = -2;
    public static final int ERR_PARAM = -3;
    public static final int ERR_STATE = -4;
    public static final int ERR_IO = -5;
    
    private final String mRootPath;
    private long mCtx = 0L;
    
    public PdfBuilder(Context context){
        mRootPath = context.getFilesDir().getAbsolutePath();
    }
    
    public void start(String fileName){
        if(mCtx != 0L) throw new IllegalStateException("already started");
        if(fileName == null || fileName.isEmpty())
            throw new IllegalArgumentException("fileName must not be empty");
        mCtx = nativeStart(mRootPath, fileName);
        if(mCtx == 0L) throw new RuntimeException("pdf_start() failed");
    }
    
    public void appendImage(Bitmap bitmap, int dstChannels, int bitsPerCh){
        if(mCtx == 0L) throw new IllegalStateException("not started");
        
        Bitmap src = ensureArgb8888(bitmap);
        int width = src.getWidth();
        int height = src.getHeight();
        
        /*
         * getPixels() でストライド問題を回避する。
         * 返り値は ARGB packed int (0xAARRGGBB)。
         * C 側は RGB 3ch で受け取るので、ここで R/G/B を byte[] に展開する。
         * Alpha は捨てる。
         */
        int[] argb = new int[width * height];
        src.getPixels(argb, 0, width, 0, 0, width, height);
        if(src != bitmap) src.recycle();
        
        byte[] rgb = new byte[width * height * 3];
        for(int i = 0; i < argb.length; i++){
            int px = argb[i];
            rgb[i * 3] = (byte) ((px >> 16) & 0xFF); // R
            rgb[i * 3 + 1] = (byte) ((px >> 8) & 0xFF); // G
            rgb[i * 3 + 2] = (byte) (px & 0xFF); // B
        }
        
        int ret = nativeAppendImage(mCtx, rgb, width, height,
                                    3, dstChannels, bitsPerCh);
        checkError(ret, "appendImage");
    }
    
    public String end(){
        if(mCtx == 0L) throw new IllegalStateException("not started");
        String path = nativeEnd(mCtx);
        mCtx = 0L;
        if(path == null) throw new RuntimeException("pdf_end() failed");
        return path;
    }
    
    public void abort(){
        if(mCtx == 0L) return;
        nativeAbort(mCtx);
        mCtx = 0L;
    }
    
    public String getCacheDir(){
        return mRootPath + "/vfs_cache";
    }
    
    private static Bitmap ensureArgb8888(Bitmap src){
        if(src.getConfig() == Bitmap.Config.ARGB_8888) return src;
        return src.copy(Bitmap.Config.ARGB_8888, false);
    }
    
    private static void checkError(int code, String ctx){
        switch(code){
            case ERR_OK:
                return;
            case ERR_ALLOC:
                throw new OutOfMemoryError("pdf: alloc failed in " + ctx);
            case ERR_COMPRESS:
                throw new RuntimeException("pdf: compress failed in " + ctx);
            case ERR_PARAM:
                throw new IllegalArgumentException("pdf: bad param in " + ctx);
            case ERR_STATE:
                throw new IllegalStateException("pdf: bad state in " + ctx);
            case ERR_IO:
                throw new RuntimeException("pdf: I/O failed in " + ctx);
            default:
                throw new RuntimeException("pdf: unknown error " + code + " in " + ctx);
        }
    }
    
    private native long nativeStart(String rootPath, String fileName);
    
    private native int nativeAppendImage(long ctx, byte[] pixels,
                                         int width, int height,
                                         int srcChannels,
                                         int dstChannels,
                                         int bitsPerChannel);
    
    private native String nativeEnd(long ctx);
    
    private native void nativeAbort(long ctx);
}