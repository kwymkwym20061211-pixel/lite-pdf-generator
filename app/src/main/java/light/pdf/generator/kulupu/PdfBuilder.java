package light.pdf.generator.kulupu;


import android.content.Context;
import android.graphics.Bitmap;

import java.nio.ByteBuffer;


/**
 * PdfBuilder  (v2: VFS ストリーミング版)
 * <p>
 * PDF をメモリに溜めずキャッシュファイルに直接書き出す。
 * end() は出力ファイルの絶対パスを返す。
 * <p>
 * 使用例:
 * <pre>
 *   PdfBuilder builder = new PdfBuilder(context);
 *   try {
 *       builder.start("output_20240101.pdf");
 *       for (Bitmap bmp : bitmaps) {
 *           builder.appendImage(bmp, PdfBuilder.CHANNELS_GRAY, PdfBuilder.BPC_4);
 *           bmp.recycle();
 *       }
 *       String pdfPath = builder.end();
 *   } catch (Exception e) {
 *       builder.abort();
 *       throw e;
 *   }
 * </pre>
 */
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
    
    /**
     * @param context アプリの Context。ルートパスとして getFilesDir() を使用する。
     */
    public PdfBuilder(Context context){
        mRootPath = context.getFilesDir().getAbsolutePath();
    }
    
    /**
     * PDF 構築を開始する。
     *
     * @param fileName 出力ファイル名 (パス区切り文字を含まないこと)
     *                 例: "output_20240101_120000.pdf"
     */
    public void start(String fileName){
        if(mCtx != 0L) throw new IllegalStateException("already started");
        if(fileName == null || fileName.isEmpty())
            throw new IllegalArgumentException("fileName must not be empty");
        mCtx = nativeStart(mRootPath, fileName);
        if(mCtx == 0L) throw new RuntimeException("pdf_start() failed");
    }
    
    /**
     * 画像を1枚追加する。
     *
     * @param bitmap      追加する画像 (任意の Config)
     * @param dstChannels 出力チャンネル数 ({@link #CHANNELS_GRAY} or {@link #CHANNELS_RGB})
     * @param bitsPerCh   出力 bits/channel (1, 2, 4, 8)
     */
    public void appendImage(Bitmap bitmap, int dstChannels, int bitsPerCh){
        if(mCtx == 0L) throw new IllegalStateException("not started");
        
        Bitmap src = ensureArgb8888(bitmap);
        int width = src.getWidth();
        int height = src.getHeight();
        
        ByteBuffer bb = ByteBuffer.allocate(width * height * 4);
        src.copyPixelsToBuffer(bb);
        byte[] pixels = bb.array();
        
        if(src != bitmap) src.recycle();
        
        int ret = nativeAppendImage(mCtx, pixels, width, height,
                                    4, dstChannels, bitsPerCh);
        checkError(ret, "appendImage");
    }
    
    /**
     * PDF を完成させてキャッシュファイルのパスを返す。
     * 呼び出し後このインスタンスは再利用不可。
     *
     * @return 出力 PDF の絶対パス
     */
    public String end(){
        if(mCtx == 0L) throw new IllegalStateException("not started");
        String path = nativeEnd(mCtx);
        mCtx = 0L;
        if(path == null) throw new RuntimeException("pdf_end() failed");
        return path;
    }
    
    /**
     * エラー時クリーンアップ。キャッシュファイルも削除される。
     * start() 後に例外が発生した場合は finally ブロックで呼ぶこと。
     */
    public void abort(){
        if(mCtx == 0L) return;
        nativeAbort(mCtx);
        mCtx = 0L;
    }
    
    /**
     * キャッシュディレクトリのパスを返す
     */
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