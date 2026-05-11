package light.pdf.generator.kulupu;

import android.content.Context;
import android.graphics.Bitmap;

public class PdfBuilder {
    
    static { System.loadLibrary("pdf_builder"); }
    
    public static final int CHANNELS_GRAY = 1;
    public static final int CHANNELS_RGB  = 3;
    public static final int BPC_1 = 1;
    public static final int BPC_2 = 2;
    public static final int BPC_4 = 4;
    public static final int BPC_8 = 8;
    
    public static final int ERR_OK       =  0;
    public static final int ERR_ALLOC    = -1;
    public static final int ERR_COMPRESS = -2;
    public static final int ERR_PARAM    = -3;
    public static final int ERR_STATE    = -4;
    public static final int ERR_IO       = -5;
    
    /** 4点クロップ座標。点順序: 左上→右上→右下→左下 (ピクセル座標) */
    public static class CropPoints {
        public final float[] x = new float[4];
        public final float[] y = new float[4];
        
        /** 画像全体をデフォルト4点で初期化 */
        public static CropPoints defaultFor(int width, int height) {
            CropPoints cp = new CropPoints();
            cp.x[0] = 0;        cp.y[0] = 0;
            cp.x[1] = width-1;  cp.y[1] = 0;
            cp.x[2] = width-1;  cp.y[2] = height-1;
            cp.x[3] = 0;        cp.y[3] = height-1;
            return cp;
        }
    }
    
    private final String mRootPath;
    private long mCtx = 0L;
    
    public PdfBuilder(Context context) {
        mRootPath = context.getFilesDir().getAbsolutePath();
    }
    
    public void start(String fileName) {
        if (mCtx != 0L) throw new IllegalStateException("already started");
        mCtx = nativeStart(mRootPath, fileName);
        if (mCtx == 0L) throw new RuntimeException("pdf_start() failed");
    }
    
    /**
     * @param bitmap     入力画像
     * @param dstChannels CHANNELS_GRAY or CHANNELS_RGB
     * @param bitsPerCh  1/2/4/8
     * @param crop       null または cropEnabled=false で変換なし
     */
    public void appendImage(Bitmap bitmap, int dstChannels, int bitsPerCh,
                            CropPoints crop) {
        if (mCtx == 0L) throw new IllegalStateException("not started");
        
        Bitmap src = ensureArgb8888(bitmap);
        int width  = src.getWidth();
        int height = src.getHeight();
        
        int[]  argb = new int[width * height];
        src.getPixels(argb, 0, width, 0, 0, width, height);
        if (src != bitmap) src.recycle();
        
        byte[] rgb = new byte[width * height * 3];
        for (int i = 0; i < argb.length; i++) {
            int px = argb[i];
            rgb[i*3  ] = (byte)((px >> 16) & 0xFF);
            rgb[i*3+1] = (byte)((px >>  8) & 0xFF);
            rgb[i*3+2] = (byte)( px        & 0xFF);
        }
        
        boolean cropEnabled = crop != null;
        float x0=0,y0=0,x1=0,y1=0,x2=0,y2=0,x3=0,y3=0;
        if (cropEnabled) {
            x0=crop.x[0]; y0=crop.y[0];
            x1=crop.x[1]; y1=crop.y[1];
            x2=crop.x[2]; y2=crop.y[2];
            x3=crop.x[3]; y3=crop.y[3];
        }
        
        int ret = nativeAppendImage(mCtx, rgb, width, height,
                                    3, dstChannels, bitsPerCh,
                                    cropEnabled,
                                    x0,y0, x1,y1, x2,y2, x3,y3);
        checkError(ret, "appendImage");
    }
    
    public String end() {
        if (mCtx == 0L) throw new IllegalStateException("not started");
        String path = nativeEnd(mCtx);
        mCtx = 0L;
        if (path == null) throw new RuntimeException("pdf_end() failed");
        return path;
    }
    
    public void abort() {
        if (mCtx == 0L) return;
        nativeAbort(mCtx);
        mCtx = 0L;
    }
    
    private static Bitmap ensureArgb8888(Bitmap src) {
        if (src.getConfig() == Bitmap.Config.ARGB_8888) return src;
        return src.copy(Bitmap.Config.ARGB_8888, false);
    }
    
    private static void checkError(int code, String ctx) {
        switch (code) {
            case ERR_OK:       return;
            case ERR_ALLOC:    throw new OutOfMemoryError("pdf: alloc failed in " + ctx);
            case ERR_COMPRESS: throw new RuntimeException("pdf: compress failed in " + ctx);
            case ERR_PARAM:    throw new IllegalArgumentException("pdf: bad param in " + ctx);
            case ERR_STATE:    throw new IllegalStateException("pdf: bad state in " + ctx);
            case ERR_IO:       throw new RuntimeException("pdf: I/O failed in " + ctx);
            default:           throw new RuntimeException("pdf: unknown error " + code);
        }
    }
    
    private native long   nativeStart(String rootPath, String fileName);
    private native int    nativeAppendImage(long ctx, byte[] pixels,
                                            int width, int height,
                                            int srcChannels, int dstChannels,
                                            int bitsPerChannel,
                                            boolean cropEnabled,
                                            float x0, float y0,
                                            float x1, float y1,
                                            float x2, float y2,
                                            float x3, float y3);
    private native String nativeEnd(long ctx);
    private native void   nativeAbort(long ctx);
}