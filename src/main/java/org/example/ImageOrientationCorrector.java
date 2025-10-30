package org.example;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class ImageOrientationCorrector {

    // 1) 路径/参数（可按需修改或用命令行覆盖）
    private static final String INPUT_DIR_PATH  = "E:\\JavaProject\\ImageCorrector\\images\\input";
    private static final String OUTPUT_DIR_PATH = "E:\\JavaProject\\ImageCorrector\\images\\out";

    private static final String TESSERACT_EXE = "tesseract";
    private static final String TESSDATA_DIR = "D:\\Tesseract-OCR\\tessdata";

    private static final String OCR_LANG = "chi_sim+eng+enm";     // 确保安装 chi_sim
    private static final String PSM_PRIMARY = "6";            // 单块文本
    private static final String PSM_FALLBACK = "11";          // 稀疏文本兜底

    private static final int ANALYSIS_MAX_SIDE = 2800;         // 方向分析时的最大边缩放

    // 去白边：像素阈值 + 行/列白像素占比 + 外扩边距
    private static final int TRIM_WHITE_THRESHOLD = 245;       // 0~255
    private static final double TRIM_WHITE_FRACTION = 0.995;   // ≥99.5% 视为白行/白列
    private static final int BORDER_PADDING_PX = 40;           // 裁后外扩

    // 调试输出
    private static final boolean DEBUG_LOG = true;

    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList("jpg","jpeg","png","bmp","tif","tiff"));

    public static void main(String[] args) throws Exception {
        String inDir = (args.length >= 1 && notBlank(args[0])) ? args[0].trim() : INPUT_DIR_PATH;
        String outDir = (args.length >= 2 && notBlank(args[1])) ? args[1].trim() : OUTPUT_DIR_PATH;

        File inputDir = new File(inDir);
        if (!inputDir.isDirectory()) throw new IOException("输入路径不存在或不是文件夹: " + inputDir.getAbsolutePath());
        File outputDir = (outDir == null || outDir.isEmpty()) ? inputDir : new File(outDir);
        if (!outputDir.exists() && !outputDir.mkdirs()) throw new IOException("无法创建输出目录: " + outputDir.getAbsolutePath());

        System.out.println("[INFO] 处理目录: " + inputDir.getAbsolutePath() + " -> " + outputDir.getAbsolutePath());
        File[] files = inputDir.listFiles();
        if (files == null) return;
        for (File f: files) {
            if (!f.isFile() || !isImageFile(f)) continue;
            try { processOneImage(f, outputDir); }
            catch (Throwable t) { System.err.println("[ERROR] " + f.getName() + ": " + t.getMessage()); }
        }
        System.out.println("[INFO] 完成");
    }


    private static void processOneImage(File imgFile, File outputDir) throws Exception {
        BufferedImage src = ImageIO.read(imgFile);
        if (src == null) throw new IOException("无法读取图像: " + imgFile.getAbsolutePath());

        int degrees = detectOrientationDegrees(src, imgFile.getName());
        BufferedImage rotated = (degrees == 0) ? src : rotateImage(src, degrees);
//        BufferedImage trimmed = trimWhiteBorders(rotated, TRIM_WHITE_THRESHOLD, TRIM_WHITE_FRACTION, BORDER_PADDING_PX);
// 新：
        BufferedImage trimmed = smartCrop(
                rotated,
                20,      // padding：统一外扩 20px（你想更紧可改 10）
                0.004,   // 行方向黑像素比例阈值（0.4% * 图像宽度）
                0.004    // 列方向黑像素比例阈值（0.4% * 图像高度）
        );

        String outName = imgFile.getName();
        File outFile = new File(outputDir, outName);
        saveImage(trimmed, outFile);
        System.out.printf("[SAVE] %s  (旋转 %d°，padding=%d)", outFile.getAbsolutePath(), degrees, BORDER_PADDING_PX);
    }

    // 方向识别：A(ocr得分)->B(投影+tie-break)->C(osd)
    private static int detectOrientationDegrees(BufferedImage src, String name) {
        try {
            BufferedImage analysis = scaleForAnalysis(src, ANALYSIS_MAX_SIDE);

            // A) OCR TSV 投票
            ScoreResult best = null;
            for (int ang : new int[]{0,90,180,270}) {
                BufferedImage cand = (ang == 0) ? analysis : rotateImage(analysis, ang);
                ScoreResult r = scoreByTesseractTSV(cand, ang, PSM_PRIMARY);
                if ((r == null || r.words == 0) && !PSM_PRIMARY.equals(PSM_FALLBACK)) {
                    r = scoreByTesseractTSV(cand, ang, PSM_FALLBACK);
                }
                if (DEBUG_LOG) System.out.println("[TSV] angle=" + ang + ", " + (r==null?"null":r));
                if (r != null && (best == null || r.score() > best.score())) best = r;
            }
            if (best != null && (best.avgConf >= 40 || best.words >= 8)) {
                if (DEBUG_LOG) System.out.println("[PICK] TSV -> " + best.angle);
                return norm90(best.angle);
            }

            // B) 投影法
            Map<Integer,ProjScore> proj = new HashMap<>();
            for (int ang : new int[]{0,90,180,270}) {
                BufferedImage cand = (ang == 0) ? analysis : rotateImage(analysis, ang);
                ProjScore ps = projectionScore(cand);
                proj.put(ang, ps);
                if (DEBUG_LOG) System.out.println("[PROJ] angle=" + ang + ", " + ps);
            }
            int projPick = pickByProjectionWithTieBreak(analysis, proj);
            if (DEBUG_LOG) System.out.println("[PICK] PROJ -> " + projPick);
            return norm90(projPick);

        } catch (Throwable t) {
            if (DEBUG_LOG) System.out.println("[WARN] detectOrientation失败: " + t);
            // C) OSD 兜底
            try {
                int osd = detectWithTesseractOSD(src).orElse(0);
                if (DEBUG_LOG) System.out.println("[PICK] OSD -> " + osd);
                return norm90(osd);
            } catch (Throwable ignore) {
                return 0;
            }
        }
    }

    private static int pickByProjectionWithTieBreak(BufferedImage analysis, Map<Integer,ProjScore> proj){
        // 先按投影分数选最大
        int bestAng = 0; ProjScore best = null;
        for (Map.Entry<Integer,ProjScore> e: proj.entrySet()){
            if (best==null || e.getValue().totalScore() > best.totalScore()){best=e.getValue(); bestAng=e.getKey();}
        }
        // 若 90 和 270 分数接近（容易混淆），做 90↔270 的加权决策
        ProjScore s90 = proj.get(90), s270 = proj.get(270);
        if (s90!=null && s270!=null){
            double diff = Math.abs(s90.totalScore() - s270.totalScore());
            double maxAbs = Math.max(Math.abs(s90.totalScore()), Math.abs(s270.totalScore()));
            if (diff <= Math.max(5.0, maxAbs*0.01)) { // 分差≤1%或≤5，认为难分
                // 1) 数字/英文 OCR 决胜（英文字母+数字，避免中文包缺失导致全空）
                int d90 = ocrDigitCount(rotateImage(analysis, 90));
                int d270 = ocrDigitCount(rotateImage(analysis, 270));
                if (DEBUG_LOG) System.out.println("[TIE] digits 90="+d90+", 270="+d270);
                if (d90 != d270) return (d90 > d270)?90:270;

                // 2) 顶部 vs 底部的文本密度（常见票据标题在上方）
                double top90 = topThirdBlackFrac(rotateImage(analysis, 90));
                double top270 = topThirdBlackFrac(rotateImage(analysis, 270));
                if (DEBUG_LOG) System.out.println("[TIE] topFrac 90="+String.format(Locale.ROOT,"%.3f",top90)+", 270="+String.format(Locale.ROOT,"%.3f",top270));
                if (Math.abs(top90-top270) > 0.002) return (top90>top270)?90:270;

                // 3) 仍然打平，默认取 90（更符合常见扫描方向）
                return 90;
            }
        }
        return bestAng;
    }

    // TSV 打分
    private static class ScoreResult {
        final int angle;        // 测试角度
        final double avgConf;   // 平均置信度（0~100）
        final int words;        // 词数量
        final int chars;        // 字符数
        ScoreResult(int angle, double avgConf, int words, int chars){this.angle=angle;this.avgConf=avgConf;this.words=words;this.chars=chars;}
        double score(){return avgConf + Math.log1p(Math.max(1,chars))*0.2 + Math.min(words,50)*0.3;}
        public String toString(){return "avgConf="+String.format(Locale.ROOT,"%.1f",avgConf)+", words="+words+", chars="+chars+", score="+String.format(Locale.ROOT,"%.1f",score());}
    }

    private static ScoreResult scoreByTesseractTSV(BufferedImage img, int angle, String psm) throws IOException, InterruptedException {
        File tmp = File.createTempFile("ioc_tsv_", ".png");
        try {
            ImageIO.write(img, "png", tmp);
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    TESSERACT_EXE, tmp.getAbsolutePath(), "stdout", "-l", OCR_LANG, "--psm", psm
            ));
            if (TESSDATA_DIR != null && !TESSDATA_DIR.isEmpty()) {
                cmd.add("--tessdata-dir"); cmd.add(TESSDATA_DIR);
            }
            // 提升低 DPI 扫描识别
            cmd.add("-c"); cmd.add("user_defined_dpi=300");
            cmd.add("tsv"); // 最后加 tsv 配置

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (TESSDATA_DIR != null && !TESSDATA_DIR.isEmpty()) {
                pb.environment().put("TESSDATA_PREFIX", new File(TESSDATA_DIR).getParent());
            }
            Process p = pb.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) { byte[] buf = new byte[8192]; int n; while((n=is.read(buf))>0) baos.write(buf,0,n);}
            int code = p.waitFor();
            String out = baos.toString(StandardCharsets.UTF_8.name());
            if (code != 0) { if (DEBUG_LOG) System.out.println("[TSV-ERR] code="+code+""+out); return null; }
            if (out.isEmpty()) return null;
            String[] lines = out.split("\r?\n");
            if (lines.length <= 1) return null;
            int confIdx = -1, textIdx = -1;
            String[] header = lines[0].split("	");
            for (int i=0;i<header.length;i++){String h=header[i].trim().toLowerCase(Locale.ROOT); if("conf".equals(h)) confIdx=i; else if("text".equals(h)) textIdx=i;}
            if (confIdx < 0) return null;
            double confSum=0;int confCnt=0;int words=0;int chars=0;
            for(int i=1;i<lines.length;i++){
                String[] cols = lines[i].split("	", -1);
                if (confIdx>=cols.length) continue;
                try{
                    int c=Integer.parseInt(cols[confIdx].trim());
                    if(c>=0 && c<=100){confSum+=c;confCnt++; if(textIdx>=0 && textIdx<cols.length){String t=cols[textIdx]; if(t!=null && !t.trim().isEmpty()){words++; chars+=t.trim().length();}}}
                }catch(NumberFormatException ignore){}
            }
            if (confCnt==0) return null;
            return new ScoreResult(angle, confSum/confCnt, words, chars);
        } finally { //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // 投影法
    private static class ProjScore {
        int angle; double rowVar; double colVar; double rowTransitions; double colTransitions; double blackFrac;
        ProjScore(double rowVar,double colVar,double rowTr,double colTr,double blackFrac){this.rowVar=rowVar;this.colVar=colVar;this.rowTransitions=rowTr;this.colTransitions=colTr;this.blackFrac=blackFrac;}
        ProjScore withAngle(int ang){this.angle=ang;return this;}
        double totalScore(){return rowVar*1.0 - colVar*0.5 + rowTransitions*0.3 - colTransitions*0.1 + Math.min(blackFrac,0.15)*10.0;}
        public String toString(){return String.format(Locale.ROOT,"rowVar=%.1f,colVar=%.1f,rowTr=%.1f,colTr=%.1f,black=%.3f,score=%.1f",rowVar,colVar,rowTransitions,colTransitions,blackFrac,totalScore());}
    }

    private static ProjScore projectionScore(BufferedImage img){
        BufferedImage bin = binarizeOtsu(img);
        int w=bin.getWidth(), h=bin.getHeight();
        int[] row = new int[h];
        int[] col = new int[w];
        int black=0;
        for(int y=0;y<h;y++){
            int cnt=0; for(int x=0;x<w;x++){int rgb=bin.getRGB(x,y)&0xFF; if(rgb==0){cnt++; black++;}}
            row[y]=cnt;
        }
        for(int x=0;x<w;x++){
            int cnt=0; for(int y=0;y<h;y++){int rgb=bin.getRGB(x,y)&0xFF; if(rgb==0){cnt++;}}
            col[x]=cnt;
        }
        double rowVar = variance(row);
        double colVar = variance(col);
        double rowTr = transitions(row, w*0.02); // 2%阈值
        double colTr = transitions(col, h*0.02);
        double blackFrac = black/(double)(w*h);
        return new ProjScore(rowVar,colVar,rowTr,colTr,blackFrac);
    }

    private static double variance(int[] a){
        double mean=0; for(int v:a) mean+=v; mean/=a.length; double v=0; for(int x:a){double d=x-mean; v+=d*d;} return v/a.length;
    }
    private static double transitions(int[] a,double thr){
        int t=0; boolean prev=false; for (int v:a){boolean cur=v>thr; if(cur!=prev) {t++; prev=cur;}} return t;
    }

    // OSD 兜底
    private static OptionalInt detectWithTesseractOSD(BufferedImage img) throws IOException, InterruptedException {
        File tmp = File.createTempFile("ioc_osd_", ".png");
        try {
            ImageIO.write(img, "png", tmp);
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    TESSERACT_EXE, tmp.getAbsolutePath(), "stdout", "--psm", "0", "-l", "osd"));
            if (TESSDATA_DIR != null && !TESSDATA_DIR.isEmpty()) {
                cmd.add("--tessdata-dir"); cmd.add(TESSDATA_DIR);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (TESSDATA_DIR != null && !TESSDATA_DIR.isEmpty()) {
                pb.environment().put("TESSDATA_PREFIX", new File(TESSDATA_DIR).getParent());
            }
            Process p = pb.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) { byte[] buf = new byte[8192]; int n; while((n=is.read(buf))>0) baos.write(buf,0,n);}
            p.waitFor();
            String out = baos.toString(StandardCharsets.UTF_8.name());
            int idx = out.indexOf("Orientation in degrees:");
            if(idx>=0){String tail=out.substring(idx+"Orientation in degrees:".length()).trim(); StringBuilder sb=new StringBuilder(); for(int i=0;i<tail.length();i++){char c=tail.charAt(i); if((c>='0'&&c<='9')||c=='-') sb.append(c); else if(sb.length()>0) break;} if(sb.length()>0){int deg=Integer.parseInt(sb.toString()); int norm=((deg%360)+360)%360; if(norm%90==0) return OptionalInt.of(norm);} }
        } finally { //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return OptionalInt.empty();
    }

    // 图像预处理/旋转工具
    private static BufferedImage scaleForAnalysis(BufferedImage src,int maxSide){
        int w=src.getWidth(), h=src.getHeight();
        double scale = Math.min(1.0, maxSide/(double)Math.max(w,h));
        if (scale>=0.999) return src;
        int nw=(int)Math.round(w*scale), nh=(int)Math.round(h*scale);
        BufferedImage out=new BufferedImage(nw,nh,BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g=out.createGraphics();
        try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(src,0,0,nw,nh,null);} finally{g.dispose();}
        return out;
    }

    private static BufferedImage rotateImage(BufferedImage src, int degrees) {
        double rads=Math.toRadians(degrees); int w=src.getWidth(), h=src.getHeight(); double sin=Math.abs(Math.sin(rads)), cos=Math.abs(Math.cos(rads)); int newW=(int)Math.floor(w*cos+h*sin), newH=(int)Math.floor(h*cos+w*sin);
        BufferedImage rotated=new BufferedImage(newW,newH,BufferedImage.TYPE_INT_RGB); Graphics2D g2d=rotated.createGraphics();
        try{g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC); g2d.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY); g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g2d.setColor(Color.WHITE); g2d.fillRect(0,0,newW,newH); AffineTransform at=new AffineTransform(); at.translate(newW/2.0,newH/2.0); at.rotate(rads); at.translate(-w/2.0,-h/2.0); g2d.drawRenderedImage(src,at);} finally{g2d.dispose();}
        return rotated;
    }

    private static BufferedImage rotateImageFast(BufferedImage src,int deg){
        double rads=Math.toRadians(deg); AffineTransform at=new AffineTransform(); double sin=Math.abs(Math.sin(rads)), cos=Math.abs(Math.cos(rads)); int w=src.getWidth(), h=src.getHeight(); int newW=(int)Math.floor(w*cos+h*sin), newH=(int)Math.floor(h*cos+w*sin); at.translate(newW/2.0,newH/2.0); at.rotate(rads); at.translate(-w/2.0,-h/2.0); AffineTransformOp op=new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR); BufferedImage out=new BufferedImage(newW,newH,BufferedImage.TYPE_BYTE_GRAY); op.filter(src,out); return out;
    }

    private static BufferedImage binarizeOtsu(BufferedImage src){
        int w=src.getWidth(), h=src.getHeight();
        // 转灰
        BufferedImage gray = new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g=gray.createGraphics(); try{g.drawImage(src,0,0,null);} finally{g.dispose();}
        // 直方图
        int[] hist=new int[256];
        for(int y=0;y<h;y++) for(int x=0;x<w;x++){int v=gray.getRGB(x,y)&0xFF; hist[v]++;}
        int total=w*h; double sum=0; for(int i=0;i<256;i++) sum+=i*hist[i];
        double sumB=0; int wB=0; double varMax=0; int thr=127;
        for(int i=0;i<256;i++){
            wB+=hist[i]; if(wB==0) continue; int wF=total-wB; if(wF==0) break; sumB+=i*hist[i]; double mB=sumB/wB; double mF=(sum-sumB)/wF; double var=(double)wB*(double)wF*(mB-mF)*(mB-mF); if(var>varMax){varMax=var; thr=i;}
        }
        BufferedImage bin=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
        int white=0; for(int y=0;y<h;y++) for(int x=0;x<w;x++){int v=gray.getRGB(x,y)&0xFF; int b=(v>thr)?255:0; if(b==255) white++; int rgb=(b<<16)|(b<<8)|b; bin.setRGB(x,y,(0xFF<<24)|rgb);}
        // 若背景黑、文字白占比异常，尝试反转，让文字更可能为黑
        double whiteFrac=white/(double)(w*h);
        if(whiteFrac<0.5){ // 背景偏黑，反转
            for(int y=0;y<h;y++) for(int x=0;x<w;x++){int v=bin.getRGB(x,y)&0xFF; int b=(v==0)?255:0; int rgb=(b<<16)|(b<<8)|b; bin.setRGB(x,y,(0xFF<<24)|rgb);}
        }
        return bin;
    }

    // tie-break：英文/数字 OCR 计数 & 顶部密度
    private static int ocrDigitCount(BufferedImage img){
        try {
            File tmp = File.createTempFile("ioc_digits_", ".png");
            try {
                ImageIO.write(img, "png", tmp);
                List<String> cmd = new ArrayList<>(Arrays.asList(
                        TESSERACT_EXE, tmp.getAbsolutePath(), "stdout","-l", "eng", "--psm", "6","-c", "tessedit_char_whitelist=0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                ));
                if (TESSDATA_DIR != null && !TESSDATA_DIR.isEmpty()) {
                    cmd.add("--tessdata-dir"); cmd.add(TESSDATA_DIR);
                }
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                if (TESSDATA_DIR != null && !TESSDATA_DIR.isEmpty()) {
                    pb.environment().put("TESSDATA_PREFIX", new File(TESSDATA_DIR).getParent());
                }
                Process p = pb.start();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = p.getInputStream()) { byte[] buf = new byte[8192]; int n; while((n=is.read(buf))>0) baos.write(buf,0,n);}
                p.waitFor();
                String out = baos.toString(StandardCharsets.UTF_8.name());
                if (out==null) return 0;
                int cnt=0; for (int i=0;i<out.length();i++){ char c=out.charAt(i); if((c>='0'&&c<='9')||(c>='A'&&c<='Z')||(c>='a'&&c<='z')) cnt++; }
                return cnt;
            } finally { //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignore){ return 0; }
    }

    private static double topThirdBlackFrac(BufferedImage img){
        BufferedImage bin = binarizeOtsu(img);
        int w=bin.getWidth(), h=bin.getHeight();
        int hTop = Math.max(1, h/3);
        int black=0, total=hTop*w;
        for(int y=0;y<hTop;y++) for(int x=0;x<w;x++){ if((bin.getRGB(x,y)&0xFF)==0) black++; }
        return black/(double)total;
    }

    // 去白边（白占比 + 外扩 padding）
    /*
    private static BufferedImage trimWhiteBorders(BufferedImage src, int thr, double whiteFrac, int padding) {
        int w = src.getWidth(), h = src.getHeight();
        int top = 0, bottom = h - 1, left = 0, right = w - 1;
        while (top <= bottom && isWhiteRowFrac(src, top, thr, whiteFrac)) top++;
        while (bottom >= top && isWhiteRowFrac(src, bottom, thr, whiteFrac)) bottom--;
        while (left <= right && isWhiteColFrac(src, left, thr, whiteFrac)) left++;
        while (right >= left && isWhiteColFrac(src, right, thr, whiteFrac)) right--;
        if (left > right || top > bottom) return src; // 全白
        left=Math.max(0,left-padding); right=Math.min(w-1,right+padding); top=Math.max(0,top-padding); bottom=Math.min(h-1,bottom+padding);
        return src.getSubimage(left, top, right-left+1, bottom-top+1);
    }
     */
    private static BufferedImage smartCrop(BufferedImage src,
                                           int padPx,
                                           double rowBlackRatio,  // 每行黑像素阈值占比（相对宽度）
                                           double colBlackRatio)  // 每列黑像素阈值占比（相对高度）
    {
        // 1) 二值化（让文字为黑，背景为白）
        BufferedImage bin = binarizeOtsu(src);
        int w = bin.getWidth(), h = bin.getHeight();

        // 2) 逐行/逐列统计黑像素数量
        int[] rowBlack = new int[h];
        int[] colBlack = new int[w];
        for (int y = 0; y < h; y++) {
            int cnt = 0;
            for (int x = 0; x < w; x++) {
                if ((bin.getRGB(x, y) & 0xFF) == 0) cnt++;
            }
            rowBlack[y] = cnt;
        }
        for (int x = 0; x < w; x++) {
            int cnt = 0;
            for (int y = 0; y < h; y++) {
                if ((bin.getRGB(x, y) & 0xFF) == 0) cnt++;
            }
            colBlack[x] = cnt;
        }

        // 3) 滑动窗口平滑（防噪点；窗口约为图像 1% 尺寸，最小 5 像素）
        int winRow = Math.max(5, h / 100);
        int winCol = Math.max(5, w / 100);
        int[] rowSmooth = movingSum(rowBlack, winRow); // 窗口内黑像素总数
        int[] colSmooth = movingSum(colBlack, winCol);

        // 4) 阈值（按比例转为绝对值）：窗口内黑像素数量阈值
        int rowThr = Math.max(1, (int) Math.round(w * rowBlackRatio)); // 每行阈值 × 窗口大小
        int colThr = Math.max(1, (int) Math.round(h * colBlackRatio));
        int rowWinThr = rowThr * winRow;
        int colWinThr = colThr * winCol;

        // 5) 寻找四边：从外向内，找到第一个“满足阈值”的位置
        int top = 0;
        while (top < h && rowSmooth[top] < rowWinThr) top++;
        int bottom = h - 1;
        while (bottom >= 0 && rowSmooth[bottom] < rowWinThr) bottom--;

        int left = 0;
        while (left < w && colSmooth[left] < colWinThr) left++;
        int right = w - 1;
        while (right >= 0 && colSmooth[right] < colWinThr) right--;

        // 6) 边界合法性检查；必要时回退为原图
        if (left >= right || top >= bottom) return src;

        // 7) 统一外扩 padding，避免太贴边
        left   = Math.max(0, left   - padPx);
        right  = Math.min(w - 1, right  + padPx);
        top    = Math.max(0, top    - padPx);
        bottom = Math.min(h - 1, bottom + padPx);

        return src.getSubimage(left, top, right - left + 1, bottom - top + 1);
    }

    // 滑动窗口和：返回与原数组等长的“窗口内和”，边缘使用夹取方式
    private static int[] movingSum(int[] a, int win) {
        int n = a.length;
        int[] out = new int[n];
        if (win <= 1) {
            System.arraycopy(a, 0, out, 0, n);
            return out;
        }
        int half = win / 2;
        int sum = 0;

        // 初始化第一个窗口
        for (int i = 0; i < win && i < n; i++) sum += a[i];
        // 为 0..half 填写
        for (int i = 0; i <= half && i < n; i++) out[i] = sum;

        // 中段滑动
        for (int i = half + 1; i < n - (win - half - 1); i++) {
            sum += a[i + (win - half - 1)];
            sum -= a[i - half - 1];
            out[i] = sum;
        }

        // 尾段：保持最后一个有效值
        for (int i = Math.max(0, n - (win - half - 1)); i < n; i++) {
            out[i] = (n - 1 - half >= 0 && n - 1 - half < n) ? out[n - 1 - half] : sum;
        }
        return out;
    }

    private static boolean isWhiteRowFrac(BufferedImage img, int y, int thr, double whiteFrac){int w=img.getWidth(); int white=0; for(int x=0;x<w;x++) if(isWhitePixel(img.getRGB(x,y),thr)) white++; return white>=whiteFrac*w;}
    private static boolean isWhiteColFrac(BufferedImage img, int x, int thr, double whiteFrac){int h=img.getHeight(); int white=0; for(int y=0;y<h;y++) if(isWhitePixel(img.getRGB(x,y),thr)) white++; return white>=whiteFrac*h;}
    private static boolean isWhitePixel(int argb, int thr){int r=(argb>>16)&0xFF, g=(argb>>8)&0xFF, b=argb&0xFF; return r>=thr && g>=thr && b>=thr;}

    // I/O & 小工具
    private static boolean isImageFile(File f){String n=f.getName().toLowerCase(Locale.ROOT); int i=n.lastIndexOf('.'); if(i<0) return false; String ext=n.substring(i+1); return IMAGE_EXTS.contains(ext);}
    private static void saveImage(BufferedImage img, File out) throws IOException {String ext=getExtension(out.getName()); if(ext==null) ext="png"; Files.createDirectories(out.toPath().getParent()); ImageIO.write(img, ext, out);}
    private static String stripExtension(String filename){int idx=filename.lastIndexOf('.'); return (idx<0)?filename:filename.substring(0,idx);}
    private static String getExtensionWithDot(String filename){int idx=filename.lastIndexOf('.'); return (idx<0)?".png":filename.substring(idx);}
    private static String getExtension(String filename){int idx=filename.lastIndexOf('.'); return (idx<0)?null:filename.substring(idx+1).toLowerCase(Locale.ROOT);}
    private static int norm90(int deg){deg=((deg%360)+360)%360; return (deg%90==0)?deg:0;}
    private static boolean notBlank(String s){return s!=null && !s.trim().isEmpty();}
}
