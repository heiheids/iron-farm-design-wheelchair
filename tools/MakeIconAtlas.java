import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成头顶图标的贴图集。
 *
 * <p><b>为什么是"白色图标 + 顶点着色"而不是"九张彩色图标"</b>：
 * 三个图标各有三种状态（绿/红/灰），每个状态一张图就是 9 张，
 * 改一次配色要重画 9 张。做成白色蒙版 + 顶点色之后，
 * 只需要 3 张白色图，颜色由渲染时的顶点色决定 —— 改配色只改代码常量。</p>
 *
 * <p><b>为什么带深色描边</b>：图标要在白天、夜晚、水下、火把旁都看得清。
 * 纯白图标在雪地/云上几乎消失。加一圈深色描边（不是投影）能保证轮廓，
 * 而且描边是画在贴图里的，不需要渲染时额外一遍。</p>
 *
 * <p>输出：{@code icon_atlas.png}，32×32 一格，横排三个，共 96×32。</p>
 * <pre>
 *   [0] 对勾  [32] 骷髅  [64] 喇叭
 * </pre>
 *
 * <p>用 java 单文件源码模式跑：{@code java tools/MakeIconAtlas.java out.png}</p>
 */
public final class MakeIconAtlas {

    /** 单格尺寸。与 IconAtlas.TILE 必须一致。 */
    private static final int TILE = 32;
    private static final int COUNT = 3;

    /** 描边颜色：近黑但不纯黑，避免在暗处糊成一团。 */
    private static final Color OUTLINE = new Color(0x20, 0x20, 0x24, 0xFF);
    /** 图形本体颜色：纯白，渲染时由顶点色覆盖。 */
    private static final Color BODY = new Color(0xFF, 0xFF, 0xFF, 0xFF);
    /** 描边宽度。32px 格子上 3px 比较稳。 */
    private static final float STROKE = 3.0f;

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "icon_atlas.png";
        BufferedImage img = new BufferedImage(TILE * COUNT, TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        drawCheck(g, 0);
        drawSkull(g, TILE);
        drawSpeaker(g, TILE * 2);

        g.dispose();
        File f = new File(out);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", f);
        System.out.println("atlas written: " + f.getAbsolutePath()
                + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    /**
     * 先描边再填白：同样的路径画两遍，第一遍粗深色，第二遍细白色。
     * 这比 Stroke 后画的顺序更简单，也不依赖合成模式。
     */
    private static void strokedStroke(Graphics2D g, java.awt.Shape path, float bodyWidth) {
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(bodyWidth + STROKE * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.setColor(BODY);
        g.setStroke(new BasicStroke(bodyWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
    }

    private static void strokedFill(Graphics2D g, java.awt.Shape shape) {
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(STROKE * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.fill(shape);
        g.setColor(BODY);
        g.fill(shape);
    }

    /** 对勾。粗线条折成两段，占满 32 格。 */
    private static void drawCheck(Graphics2D g, int ox) {
        Path2D p = new Path2D.Float();
        p.moveTo(ox + 6.0, 17.0);
        p.lineTo(ox + 13.0, 24.0);
        p.lineTo(ox + 26.0, 8.0);
        strokedStroke(g, p, 5.0f);
    }

    /** 骷髅：圆颅骨 + 方下颌 + 两个眼窝（挖空）+ 牙齿。 */
    private static void drawSkull(Graphics2D g, int ox) {
        // 颅骨主体
        Ellipse2D cranium = new Ellipse2D.Float(ox + 5.0f, 3.0f, 22.0f, 19.0f);
        strokedFill(g, cranium);
        // 下颌
        RoundRectangle2D jaw = new RoundRectangle2D.Float(ox + 10.0f, 20.0f, 12.0f, 9.0f, 4.0f, 4.0f);
        g.setColor(OUTLINE);
        g.fill(new RoundRectangle2D.Float(ox + 8.5f, 18.5f, 15.0f, 12.0f, 5.0f, 5.0f));
        g.setColor(BODY);
        g.fill(jaw);

        // 眼窝：用描边色填（深色），不是挖空 —— 挖空在透视渲染里会露出背景，
        // 看起来像两个洞，反而更奇怪。深色填充在亮背景上也清楚。
        g.setColor(OUTLINE);
        g.fill(new Ellipse2D.Float(ox + 9.0f, 9.0f, 6.5f, 7.0f));
        g.fill(new Ellipse2D.Float(ox + 17.0f, 9.0f, 6.5f, 7.0f));

        // 鼻子：小三角
        Path2D nose = new Path2D.Float();
        nose.moveTo(ox + 16.0, 16.0);
        nose.lineTo(ox + 14.0, 19.0);
        nose.lineTo(ox + 18.0, 19.0);
        nose.closePath();
        g.fill(nose);

        // 牙齿：两条竖线
        g.setStroke(new BasicStroke(2.0f));
        g.drawLine(ox + 13, 21, ox + 13, 28);
        g.drawLine(ox + 19, 21, ox + 19, 28);
    }

    /** 喇叭：锥形号角 + 两道声波弧。 */
    private static void drawSpeaker(Graphics2D g, int ox) {
        // 号角主体
        Path2D horn = new Path2D.Float();
        horn.moveTo(ox + 4.0, 13.0);
        horn.lineTo(ox + 11.0, 13.0);
        horn.lineTo(ox + 19.0, 4.0);
        horn.lineTo(ox + 19.0, 28.0);
        horn.lineTo(ox + 11.0, 19.0);
        horn.lineTo(ox + 4.0, 19.0);
        horn.closePath();
        strokedFill(g, horn);

        // 两道声波弧
        strokedStroke(g, new Arc2D.Float(ox + 15, 8, 10, 16, -60, 120, Arc2D.OPEN), 2.5f);
        strokedStroke(g, new Arc2D.Float(ox + 20, 4, 8, 24, -55, 110, Arc2D.OPEN), 2.5f);
    }
}
