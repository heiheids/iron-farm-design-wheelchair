import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成模组图标。
 *
 * <p>纯 Java + ImageIO，不引任何第三方库（本机也没有 Python/PIL）。
 * 用 java 的单文件源码模式直接跑：{@code java tools/MakeIcon.java}。</p>
 *
 * <p>设计思路（对应程序特点）：</p>
 * <ul>
 *   <li>深色圆角底 —— 模组图标在浅色/深色界面上都要能看清；</li>
 *   <li>铁傀儡的脸 —— 直接点明「铁傀儡」；</li>
 *   <li>底部的红色「×」与绿色「√」—— 对应程序的核心功能：判定条件是否具备；</li>
 *   <li>右侧一个橙色小方块 —— 代表「可刷范围」的框。</li>
 * </ul>
 * <p>元素故意压到 4 个，128px 缩到 16px 仍能分辨轮廓。</p>
 */
public final class MakeIcon {

    private static final int SIZE = 128;

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "icon.png";
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 底色：深板岩灰的圆角方块
        g.setColor(new Color(0x2B2F36));
        g.fill(new RoundRectangle2D.Float(2, 2, SIZE - 4, SIZE - 4, 28, 28));

        // 内层浅色底，给铁傀儡面部一个对比
        g.setColor(new Color(0xD8DCE2));
        g.fill(new RoundRectangle2D.Float(14, 14, SIZE - 28, SIZE - 28, 20, 20));

        // ---- 铁傀儡的脸 ----
        // 头部：上宽下窄的梯形
        Path2D head = new Path2D.Float();
        head.moveTo(38, 40);
        head.lineTo(90, 40);
        head.lineTo(84, 66);
        head.lineTo(44, 66);
        head.closePath();
        g.setColor(new Color(0x8E949C));
        g.fill(head);

        // 眼睛：两道竖条，默认的"怒视"造型
        g.setColor(new Color(0x1F2226));
        g.fillRect(50, 47, 9, 11);
        g.fillRect(69, 47, 9, 11);

        // 鼻子：一小段横杠
        g.setColor(new Color(0x6E747C));
        g.fillRect(60, 56, 8, 8);

        // 身体：下方的梯形
        Path2D body = new Path2D.Float();
        body.moveTo(44, 70);
        body.lineTo(84, 70);
        body.lineTo(88, 96);
        body.lineTo(40, 96);
        body.closePath();
        g.setColor(new Color(0x9AA0A8));
        g.fill(body);

        // ---- 判定符号：绿勾 + 红叉（本程序的核心隐喻） ----
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // 绿勾
        g.setColor(new Color(0x3FBF5F));
        Path2D check = new Path2D.Float();
        check.moveTo(26, 84);
        check.lineTo(36, 95);
        check.lineTo(53, 74);
        g.draw(check);

        // 红叉
        g.setColor(new Color(0xD8433C));
        Path2D cross = new Path2D.Float();
        cross.moveTo(96, 76);
        cross.lineTo(110, 90);
        cross.moveTo(110, 76);
        cross.lineTo(96, 90);
        g.draw(cross);

        // ---- 可刷范围：右侧橙色小框 ----
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0xE8912F));
        g.drawRect(99, 20, 16, 16);

        g.dispose();
        File out = new File(outPath);
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", out);
        System.out.println("icon written: " + out.getAbsolutePath());
    }
}
