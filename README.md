# ImageOrientationCorrector
Tesseract-OCR简介
Tesseract是一款优秀的开源OCR(Optical Character Recognition, 光学字符识别) 软件，OCR 是图像识别领域中的一个子领域，该领域专注于对图片中的文字信息进行识别并转换成能被常规文本编辑器编辑的文本。目前Tesseract由Google维护改进，已发展到5.0版本，从4.0版本起增加了基于LSTM神经网络的识别引擎。
Tesseract且可以配合 net.sourceforge.tess4j:tess4j:4.5.5 可以提供简洁的 Java API。

1、下载软件安装包
https://digi.bib.uni-mannheim.de/tesseract/tesseract-ocr-w64-setup-v5.0.0.20190623.exe
按照提示进行安装
2、配置环境变量
在【系统变量】Path添加Tesseract-OCR文件夹的目录，例如【C:\Program Files\Tesseract-OCR】，方便任意处调用tesseract 命令
在【系统变量】中新建系统变量 TESSDATA_PREFIX，输入语言包所在的路径，例如【C:\Program Files\Tesseract-OCR\tessdata】，用于加载语言
3、检验安装
cmd 窗口输入 tesseract -v，能输出版本信息即安装成功

在打印信息中可以看到对应的版本号v5.0.0-rc1.20190623以及各种依赖库文件的版本号，表示安装成功
4、安装语言包
进入tesseract的github文档页（https://tesseract-ocr.github.io/tessdoc/），找到5.0.0.x目录下的Traineddata Files目录

点击tessdata，进入到tessdata语言包的github仓后，可以看到很多以语言简称为文件名、traineddata为后缀的文件，其中eng.traineddata和chi_sim.traineddata是中文和英文包，下载语言包到本地后解压，放入【\Tesseract-OCR\tessdata】目录下


5、测试
检查语言包是否完成安装
tesseract.exe --list-langs

执行后看到了chi_sim、eng和enm 3种语言，说明对应的语言类型安装成功

tesseract --help可以打印出大部分的命令，其中识别图片的命令如下形式

tesseract imagename|imagelist|stdin outputbase|stdout [options…] [configfile…]

imagename|imagelist|stdin：可以是单个图片文件名称、多个图片文件组成的清单或者标准输入stdin
outputbase|stdout：可以是输出文件名称或者标准输出stdout
options：可以配置语言种类、设置识别引擎、分页模式等

解析单个文件test.png，在标准输出(命令行界面)打印解析结果，用-l参数带chi_sim表示使用简体中文语言：

tesseract test.png stdout -l chi_sim

出现乱码的情况
# 强制命令提示符显示 UTF-8
chcp 65001
# 运行原始命令
tesseract test.png stdout -l chi_sim
也可以将stdout改为其它的字符串（第2个参数改为输出文件名称，不用带txt后缀），这样会将识别的结果写入到以该字符串命名的txt文件中，在当前目录下就会生成一个result.txt的文件，文件内容就是识别出来的文字内容
tesseract test.png result -l chi_sim
从识别的结果看，对于这种清晰度较高的图片识别效果较好

6、具体应用(Java 17)
批量处理发票/回单等扫描件：
自动识别文字方向（0/90/180/270），校正为正向
智能裁剪为票据主体，保留均匀小边距
输出文件与原文件同名，写入到指定的输出目录


6.1 三重判定的稳健方向识别： 
OCR-TSV 投票：--psm 6/11（单文本块6，稀疏文本11）chi_sim+eng（简体中文和英语语言），以平均置信度 + 文本长度打分
投影法：Otsu 二值 + 行/列投影方差与过渡次数
90°↔270°平局裁判：英文/数字 OCR 计数 + 顶部文本密度；
OSD 兜底（--psm 0 -l osd）

智能裁剪：投影 + 滑动窗口找票据主体，统一外扩 padding，避免裁得过紧
批量处理常见图像格式（jpg/jpeg/png/bmp/tif/tiff）
日志可读：输出每一步的评分/判定，便于排查

6.2关键配置：
// 目录（可被命令行参数覆盖）
private static final String INPUT_DIR_PATH  = "E:\\JavaProject\\ImageCorrector\\images\\input";
private static final String OUTPUT_DIR_PATH = "E:\\JavaProject\\ImageCorrector\\images\\out";

// Tesseract 与 tessdata
private static final String TESSERACT_EXE = "D:\\\\Tesseract-OCR\\\\tesseract.exe"; // 或 "tesseract"
private static final String TESSDATA_DIR  = "D:\\\\Tesseract-OCR\\\\tessdata";      // 指向含 *.traineddata 的目录

// OCR 语言与 PSM
private static final String OCR_LANG = "chi_sim+eng";  // 中文+英文
private static final String PSM_PRIMARY  = "6";        // 单块文本
private static final String PSM_FALLBACK = "11";       // 稀疏文本兜底

// 分析分辨率（影响方向判定质量与速度）
private static final int ANALYSIS_MAX_SIDE = 2800;     // 建议 2000~3000

// 智能裁剪参数（在 processOneImage 调用 smartCrop 时设定）
/*
BufferedImage trimmed = smartCrop(
    rotated,
    20,     // padding: 统一外扩 20px（改小更紧：10）
    0.004,  // 行方向黑像素比例阈值（0.4% * 图像宽度）
    0.004   // 列方向黑像素比例阈值（0.4% * 图像高度）
);
*/
运行时在控制台可看到类似日志

angle：测试角度
avgConf：平均置信度
words：词数量
chars：字符数
score：分数
6.3 工作原理
方向识别
TSV 投票：对 0/90/180/270 四角分别识别 TSV，统计平均置信度与文本长度，按得分选角度。
投影法：若 TSV 信息不足，转二值图后对行/列黑像素进行统计，利用方差与过渡次数区分“横排文本 vs 竖向结构”。
90/270 决胜：若两者投影非常接近，则用（a）英文/数字 OCR 字符数；（b）顶部区域黑像素密度，进行加权裁判。
OSD 兜底：仍无法判断时，使用 Tesseract OSD。

智能裁剪
对二值图做行/列投影，用滑动窗口平滑后以阈值找四个边；
统一外扩 padding，得到观感自然的主体区域。



