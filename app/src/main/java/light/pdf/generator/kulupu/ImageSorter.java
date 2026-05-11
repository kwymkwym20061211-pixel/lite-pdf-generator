package light.pdf.generator.kulupu;


import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * ImageSorter
 * <p>
 * ディレクトリ URI から画像 URI を収集し、指定されたソート順で返す。
 */
public class ImageSorter{
    
    public enum SortKey{
        DATE,   /* 作成日時 */
        NAME,   /* ファイル名 Unicode 順 */
        NUMBER  /* ファイル名から数字部分を抽出して数値順 */
    }
    
    
    public enum SortOrder{
        ASC, DESC
    }
    
    
    /**
     * ソート設定
     */
    public static class SortConfig{
        public SortKey key = SortKey.NAME;
        public SortOrder order = SortOrder.ASC;
    }
    
    
    private static final Pattern NUM_PATTERN = Pattern.compile("(\\d+)");
    
    /* ---------------------------------------------------------------- */
    
    /**
     * ディレクトリ URI (ACTION_OPEN_DOCUMENT_TREE の結果) から
     * 画像 URI リストをソートして返す。
     */
    public static List<Uri> listSorted(Context ctx, Uri treeUri, SortConfig cfg){
        List<ImageEntry> entries = new ArrayList<>();
        
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        
        try(Cursor c = ctx.getContentResolver().query(
                childrenUri, projection, null, null, null)){
            if(c == null) return new ArrayList<>();
            while(c.moveToNext()){
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long date = c.getLong(3);
                
                /* ディレクトリはスキップ */
                if("vnd.android.document/directory".equals(mime)) continue;
                /* image/* のみ対象 */
                if(mime != null && !mime.startsWith("image/")) continue;
                /* mime が null の場合は拡張子チェック */
                if(mime == null){
                    String lower = name != null ? name.toLowerCase() : "";
                    if(!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
                       && !lower.endsWith(".png") && !lower.endsWith(".webp")
                       && !lower.endsWith(".gif") && !lower.endsWith(".bmp")) continue;
                }
                
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                entries.add(new ImageEntry(uri, name, date));
            }
        } catch(Exception e){
            return new ArrayList<>();
        }
        
        sort(entries, cfg);
        
        List<Uri> result = new ArrayList<>(entries.size());
        for(ImageEntry e : entries) result.add(e.uri);
        return result;
    }
    
    /* ---------------------------------------------------------------- */
    /* ソート                                                            */
    /* ---------------------------------------------------------------- */
    
    private static void sort(List<ImageEntry> list, SortConfig cfg){
        Collections.sort(list, (a, b) -> {
            int cmp;
            switch(cfg.key){
                case DATE:
                    cmp = Long.compare(a.date, b.date);
                    break;
                case NUMBER:
                    cmp = compareByNumber(a.name, b.name);
                    break;
                case NAME:
                default:
                    cmp = a.name.compareTo(b.name);
                    break;
            }
            return cfg.order == SortOrder.DESC ? -cmp : cmp;
        });
    }
    
    /**
     * 名前から最初の数字列を抽出して数値比較。
     * 数字がない場合は名前比較にフォールバック。
     */
    private static int compareByNumber(String a, String b){
        long na = extractNumber(a);
        long nb = extractNumber(b);
        if(na < 0 && nb < 0) return a.compareTo(b);
        if(na < 0) return 1; /* 数字なし → 後ろ */
        if(nb < 0) return -1;
        int cmp = Long.compare(na, nb);
        return cmp != 0 ? cmp : a.compareTo(b); /* 同値なら名前順 */
    }
    
    private static long extractNumber(String name){
        Matcher m = NUM_PATTERN.matcher(name);
        if(!m.find()) return -1;
        try{return Long.parseLong(m.group(1));} catch(NumberFormatException e){return -1;}
    }
    
    /* ---------------------------------------------------------------- */
    /* 内部データクラス                                                  */
    /* ---------------------------------------------------------------- */
    
    
    private static class ImageEntry{
        final Uri uri;
        final String name;
        final long date;
        
        ImageEntry(Uri uri, String name, long date){
            this.uri = uri;
            this.name = name;
            this.date = date;
        }
    }
}