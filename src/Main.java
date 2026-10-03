import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Chrono —— 黑色风格桌面时钟
 * -------------------------------------------------------------------
 * 功能：
 *   1. 时钟     ：数字时间 + 模拟表盘 + 日期星期
 *   2. 悬浮窗   ：无边框置顶小窗，实时显示时间，可拖动
 *   3. 计时器   ：秒表计时，支持暂停 / 计次（分段）
 *   4. 倒计时   ：预设与自定义时长，进度条显示，结束提醒
 *   5. 定时提醒 ：到达指定日期时刻弹出提醒（一次性）
 *   6. 每日闹钟 ：每天固定时刻响铃（可开关）
 * 界面：Swing 全自绘暗色主题，无边框窗口 + 纯黑自定义标题栏
 */
public class Main {

    /* ==================== 主题配色 ==================== */
    static final Color BG        = new Color(0x1E1F22);
    static final Color TITLE_BG  = new Color(0x0A0A0A);   // 标题栏（纯黑）
    static final Color PANEL     = new Color(0x2B2D30);
    static final Color CARD      = new Color(0x232528);
    static final Color FIELD     = new Color(0x161719);
    static final Color TEXT      = new Color(0xE8EAED);
    static final Color MUTED     = new Color(0x9AA0A6);
    static final Color ACCENT    = new Color(0x3B82F6);
    static final Color ACCENT_HI = new Color(0x2F6FE0);
    static final Color BORDER    = new Color(0x3A3D41);
    static final Color CLOSE_HI  = new Color(0xE81123);
    static final Color OK        = new Color(0x54C784);
    static final Color ERR       = new Color(0xEF5350);

    /* ==================== 字体 ==================== */
    static final Font UI      = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
    static final Font SMALL   = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    static final Font TITLE_F = new Font("Microsoft YaHei UI", Font.BOLD, 13);
    static final Font DIGIT_B = new Font("Consolas", Font.BOLD, 54);
    static final Font DIGIT_M = new Font("Consolas", Font.BOLD, 26);

    /* ==================== 全局状态 ==================== */
    static JFrame frame;
    static CardLayout cardLayout;
    static JPanel cards;
    static JLabel statusLabel;
    static final List<JButton> tabButtons = new ArrayList<>();
    static final String[] CARD_NAMES = {"clock", "timer", "countdown", "alarm"};
    static final String[] CARD_TITLES = {"时钟", "计时器", "倒计时", "闹钟提醒"};
    static String currentCard = "clock";
    static Preferences prefs;
    static TrayIcon trayIcon;
    static boolean closeToTray = true;
    static final AlarmSound SOUND = new AlarmSound();
    static final List<Alarm> alarms = new ArrayList<>();
    static long lastSecond = -1;

    /* 悬浮窗 */
    static OverlayClock overlay;
    static boolean overlayWanted = true;

    /* 计时器状态 */
    static boolean swRunning;
    static long swStartNs, swAccumNs;
    static long lastLapElapsed;
    static int lapCount;
    static JLabel swDisplay;
    static JLabel swHint;
    static JPanel lapList;
    static JButton swStartBtn;
    static final List<long[]> laps = new ArrayList<>();   // {分段纳秒, 总纳秒}

    /* 倒计时状态 */
    static boolean cdRunning;
    static long cdTotalMs, cdRemainMs, cdEndNs;
    static JLabel cdDisplay, cdHint;
    static Bar cdBar;
    static JTextField cdH, cdM, cdS;
    static JButton cdStartBtn;

    /* 时钟 */
    static BigTime clockTime;
    static JLabel clockDate;
    static AnalogClock analog;

    /* 提醒窗口 */
    static final List<AlertWindow> openAlerts = new ArrayList<>();

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /* ==================== 程序入口 ==================== */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::start);
    }

    static void start() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        prefs = Preferences.userRoot().node("chrono");
        closeToTray = prefs.getBoolean("closeToTray", true);
        loadAlarms();

        frame = new JFrame("Chrono 时钟");
        frame.setUndecorated(true);                 // 去掉系统标题栏，才能做成纯黑
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(1180, 780);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setLocationRelativeTo(null);
        try {
            frame.setIconImage(appIcon(64));
        } catch (Exception ignored) {
        }

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, BORDER));
        frame.setContentPane(root);

        root.add(buildTitleBar(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                onWindowClose();
            }
        });
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                frame.getContentPane().revalidate();
            }
        });

        setupTray();

        // 主循环：每 100ms 刷新一次
        new javax.swing.Timer(100, e -> tick()).start();

        frame.setVisible(true);
        if (overlayWanted) showOverlay();
    }

    /* ==================== 纯黑自定义标题栏 ==================== */
    static JPanel buildTitleBar() {
        final JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(TITLE_BG);
        bar.setPreferredSize(new Dimension(0, 40));

        JLabel title = new JLabel("  \u25C6  Chrono 时钟");
        title.setFont(TITLE_F);
        title.setForeground(new Color(0xD5D8DD));
        bar.add(title, BorderLayout.WEST);

        JButton overlayBtn = flatButton("悬浮窗", PANEL, TEXT, new Color(0x3C3F44));
        overlayBtn.setFont(SMALL);
        overlayBtn.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        overlayBtn.setToolTipText("显示 / 隐藏桌面悬浮时钟");
        overlayBtn.addActionListener(e -> toggleOverlay());

        JButton gearBtn = windowButton("\u2699", "设置");
        gearBtn.addActionListener(e -> showSettings(gearBtn));

        JButton minBtn   = windowButton("\u2013", "最小化");
        JButton maxBtn   = windowButton("\u25A1", "最大化");
        JButton closeBtn = windowButton("\u2715", "关闭");
        minBtn.addActionListener(e -> frame.setState(Frame.ICONIFIED));
        maxBtn.addActionListener(e -> toggleMaximize());
        closeBtn.addActionListener(e -> onWindowClose());

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        btns.setOpaque(false);
        btns.add(overlayBtn);
        btns.add(gearBtn);
        btns.add(minBtn);
        btns.add(maxBtn);
        btns.add(closeBtn);
        bar.add(btns, BorderLayout.EAST);

        final Point[] press = {null};
        bar.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                press[0] = e.getPoint();
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) toggleMaximize();
            }
        });
        bar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (press[0] == null) return;
                if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
                    frame.setExtendedState(Frame.NORMAL);
                }
                Point loc = frame.getLocation();
                frame.setLocation(loc.x + e.getX() - press[0].x, loc.y + e.getY() - press[0].y);
            }
        });
        return bar;
    }

    static JButton windowButton(String glyph, String tip) {
        final Color normal = TITLE_BG;
        final Color hover = "\u2715".equals(glyph) ? CLOSE_HI : new Color(0x242424);
        final JButton b = new JButton(glyph);
        b.setToolTipText(tip);
        b.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 13));
        b.setForeground(new Color(0xC8CBD0));
        b.setBackground(normal);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(46, 40));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                b.setBackground(hover);
                b.setForeground(Color.WHITE);
            }
            @Override public void mouseExited(MouseEvent e) {
                b.setBackground(normal);
                b.setForeground(new Color(0xC8CBD0));
            }
        });
        return b;
    }

    static void toggleMaximize() {
        if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
            frame.setExtendedState(Frame.NORMAL);
        } else {
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    static void onWindowClose() {
        if (closeToTray && trayIcon != null) {
            frame.setVisible(false);
            trayIcon.displayMessage("Chrono 时钟", "已最小化到托盘，闹钟与提醒仍在后台运行",
                    TrayIcon.MessageType.INFO);
        } else {
            exitApp();
        }
    }

    static void exitApp() {
        try {
            prefs.flush();
        } catch (Exception ignored) {
        }
        SOUND.stop();
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        System.exit(0);
    }

    static void showSettings(Component invoker) {
        JPopupMenu m = darkMenu();
        JCheckBoxMenuItem trayItem = new JCheckBoxMenuItem("关闭窗口时最小化到托盘", closeToTray);
        trayItem.setFont(UI);
        trayItem.setForeground(TEXT);
        trayItem.setBackground(PANEL);
        trayItem.addActionListener(e -> {
            closeToTray = trayItem.isSelected();
            prefs.putBoolean("closeToTray", closeToTray);
            setStatus(closeToTray ? "已设置为：关闭时最小化到托盘" : "已设置为：关闭时退出程序", MUTED);
        });
        JMenuItem overlayItem = new JMenuItem(overlay != null && overlay.isVisible() ? "隐藏悬浮窗" : "显示悬浮窗");
        overlayItem.setFont(UI);
        overlayItem.setForeground(TEXT);
        overlayItem.setBackground(PANEL);
        overlayItem.addActionListener(e -> toggleOverlay());
        JMenuItem exitItem = new JMenuItem("退出程序");
        exitItem.setFont(UI);
        exitItem.setForeground(TEXT);
        exitItem.setBackground(PANEL);
        exitItem.addActionListener(e -> exitApp());
        m.add(overlayItem);
        m.addSeparator();
        m.add(trayItem);
        m.addSeparator();
        m.add(exitItem);
        m.show(invoker, 0, invoker.getHeight());
    }

    static JPopupMenu darkMenu() {
        JPopupMenu m = new JPopupMenu();
        m.setBackground(PANEL);
        m.setBorder(BorderFactory.createLineBorder(BORDER));
        return m;
    }

    /* ==================== 左侧导航 + 内容区 ==================== */
    static JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(BG);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(new Color(0x18191B));
        side.setPreferredSize(new Dimension(168, 0));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER));
        for (int i = 0; i < CARD_NAMES.length; i++) {
            side.add(tabButton(CARD_TITLES[i], CARD_NAMES[i]));
        }
        body.add(side, BorderLayout.WEST);

        cardLayout = new CardLayout();
        cards = new JPanel(cardLayout);
        cards.setBackground(BG);
        cards.add(cardClock(), "clock");
        cards.add(cardTimer(), "timer");
        cards.add(cardCountdown(), "countdown");
        cards.add(cardAlarm(), "alarm");

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(BG);
        right.setBorder(BorderFactory.createEmptyBorder(14, 14, 6, 14));
        right.add(cards, BorderLayout.CENTER);
        right.add(buildStatusBar(), BorderLayout.SOUTH);
        body.add(right, BorderLayout.CENTER);
        highlightTab("clock");
        return body;
    }

    static JButton tabButton(String text, String card) {
        final JButton b = new JButton(text);
        b.setFont(UI);
        b.setForeground(MUTED);
        b.setBackground(new Color(0x18191B));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        b.setPreferredSize(new Dimension(168, 46));
        b.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> {
            currentCard = card;
            cardLayout.show(cards, card);
            highlightTab(card);
        });
        tabButtons.add(b);
        return b;
    }

    static void highlightTab(String card) {
        for (int i = 0; i < CARD_NAMES.length; i++) {
            boolean on = CARD_NAMES[i].equals(card);
            JButton b = tabButtons.get(i);
            b.setBackground(on ? PANEL : new Color(0x18191B));
            b.setForeground(on ? TEXT : MUTED);
            b.setFont(on ? new Font("Microsoft YaHei UI", Font.BOLD, 14) : UI);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, on ? 3 : 0, 0, 0, ACCENT),
                    BorderFactory.createEmptyBorder(0, on ? 19 : 22, 0, 12)));
        }
    }

    static JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(SMALL);
        statusLabel.setForeground(MUTED);
        bar.add(statusLabel, BorderLayout.WEST);
        JLabel tip = new JLabel("Ctrl+Enter 无  |  数据自动保存");
        tip.setFont(SMALL);
        tip.setForeground(new Color(0x6B6F76));
        bar.add(tip, BorderLayout.EAST);
        return bar;
    }

    static void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    /* ==================== 卡片 1：时钟 ==================== */
    static JPanel cardClock() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(BG);

        analog = new AnalogClock();
        analog.setPreferredSize(new Dimension(250, 250));

        clockTime = new BigTime();

        clockDate = new JLabel(" ");
        clockDate.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 16));
        clockDate.setForeground(MUTED);

        JLabel zone = new JLabel(" ");
        zone.setFont(SMALL);
        zone.setForeground(new Color(0x6B6F76));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.insets = new Insets(8, 8, 8, 8);
        gc.gridy = 0;
        p.add(analog, gc);
        gc.gridy = 1;
        p.add(clockTime, gc);
        gc.gridy = 2;
        p.add(clockDate, gc);
        gc.gridy = 3;
        p.add(zone, gc);
        new javax.swing.Timer(1000, e -> zone.setText(
                "时区：" + java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT))).start();
        return p;
    }

    /* ==================== 卡片 2：计时器 ==================== */
    static JPanel cardTimer() {
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel(new GridBagLayout());
        top.setBackground(CARD);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(24, 20, 24, 20)));

        swDisplay = monoLabel("00:00:00.00", DIGIT_B, TEXT);
        swHint = new JLabel("已暂停");
        swHint.setFont(UI);
        swHint.setForeground(MUTED);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        top.add(swDisplay, gc);
        gc.gridy = 1;
        gc.insets = new Insets(6, 0, 0, 0);
        top.add(swHint, gc);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        btns.setOpaque(false);
        swStartBtn = flatButton("开 始", ACCENT, Color.WHITE, ACCENT_HI);
        swStartBtn.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 14));
        swStartBtn.setBorder(BorderFactory.createEmptyBorder(9, 30, 9, 30));
        swStartBtn.addActionListener(e -> toggleStopwatch());

        JButton lapBtn = flatButton("计 次", PANEL, TEXT, new Color(0x3C3F44));
        lapBtn.addActionListener(e -> addLap());

        JButton resetBtn = flatButton("重 置", PANEL, TEXT, new Color(0x3C3F44));
        resetBtn.addActionListener(e -> resetStopwatch());

        btns.add(swStartBtn);
        btns.add(lapBtn);
        btns.add(resetBtn);
        gc.gridy = 2;
        gc.insets = new Insets(20, 0, 0, 0);
        top.add(btns, gc);
        p.add(top, BorderLayout.NORTH);

        lapList = new JPanel();
        lapList.setLayout(new BoxLayout(lapList, BoxLayout.Y_AXIS));
        lapList.setBackground(FIELD);
        JScrollPane sp = darkScroll(lapList);
        sp.setBorder(BorderFactory.createLineBorder(BORDER));
        p.add(sp, BorderLayout.CENTER);
        showLapPlaceholder();
        return p;
    }

    static void showLapPlaceholder() {
        lapList.removeAll();
        JLabel l = new JLabel("点击「计次」记录分段用时");
        l.setFont(SMALL);
        l.setForeground(new Color(0x6B6F76));
        l.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        lapList.add(row(l));
        lapList.revalidate();
        lapList.repaint();
    }

    static JPanel row(JComponent inner) {
        JPanel r = new JPanel(new BorderLayout());
        r.setBackground(FIELD);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        r.add(inner, BorderLayout.WEST);
        return r;
    }

    static void toggleStopwatch() {
        if (swRunning) {
            swAccumNs += System.nanoTime() - swStartNs;
            swRunning = false;
            swStartBtn.setText("继 续");
            swHint.setText("已暂停");
            setStatus("计时已暂停", MUTED);
        } else {
            swStartNs = System.nanoTime();
            swRunning = true;
            swStartBtn.setText("暂 停");
            swHint.setText("计时中…");
            setStatus("计时已开始", OK);
        }
    }

    static void addLap() {
        long total = swElapsedNs();
        if (total == 0 && !swRunning && swAccumNs == 0) {
            setStatus("尚未开始计时", ERR);
            return;
        }
        long seg = total - lastLapElapsed;
        lastLapElapsed = total;
        lapCount++;
        laps.add(new long[]{seg, total});
        if (lapCount == 1) lapList.removeAll();
        JPanel r = new JPanel(new GridBagLayout());
        r.setBackground(FIELD);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        r.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x26282B)));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 16, 0, 8);
        JLabel n = new JLabel("第 " + lapCount + " 次");
        n.setFont(SMALL);
        n.setForeground(MUTED);
        n.setPreferredSize(new Dimension(90, 22));
        JLabel s = new JLabel("+" + formatStopwatch(seg));
        s.setFont(new Font("Consolas", Font.PLAIN, 13));
        s.setForeground(TEXT);
        JLabel t = new JLabel(formatStopwatch(total));
        t.setFont(new Font("Consolas", Font.PLAIN, 13));
        t.setForeground(MUTED);
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        r.add(n, gc);
        gc.gridx = 1;
        gc.weightx = 0;
        r.add(s, gc);
        gc.gridx = 2;
        r.add(t, gc);
        lapList.add(r, 0);
        lapList.revalidate();
        lapList.repaint();
        setStatus("已记录第 " + lapCount + " 次", MUTED);
    }

    static void resetStopwatch() {
        swRunning = false;
        swAccumNs = 0;
        lastLapElapsed = 0;
        lapCount = 0;
        laps.clear();
        swStartBtn.setText("开 始");
        swHint.setText("已暂停");
        swDisplay.setText("00:00:00.00");
        showLapPlaceholder();
        setStatus("计时器已重置", MUTED);
    }

    static long swElapsedNs() {
        return swRunning ? swAccumNs + (System.nanoTime() - swStartNs) : swAccumNs;
    }

    /* ==================== 卡片 3：倒计时 ==================== */
    static JPanel cardCountdown() {
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel(new GridBagLayout());
        top.setBackground(CARD);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(22, 20, 22, 20)));

        cdDisplay = monoLabel("00:00:00", DIGIT_B, TEXT);
        cdBar = new Bar();
        cdBar.setPreferredSize(new Dimension(520, 10));
        cdHint = new JLabel("设置时长后点击开始");
        cdHint.setFont(UI);
        cdHint.setForeground(MUTED);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        top.add(cdDisplay, gc);
        gc.gridy = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(18, 0, 0, 0);
        top.add(cdBar, gc);
        gc.gridy = 2;
        gc.fill = GridBagConstraints.NONE;
        gc.insets = new Insets(10, 0, 0, 0);
        top.add(cdHint, gc);

        /* 时长输入 */
        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        inputRow.setOpaque(false);
        cdH = darkField("00", 4);
        cdM = darkField("05", 4);
        cdS = darkField("00", 4);
        inputRow.add(mutedLabel("时"));
        inputRow.add(cdH);
        inputRow.add(mutedLabel("分"));
        inputRow.add(cdM);
        inputRow.add(mutedLabel("秒"));
        inputRow.add(cdS);
        inputRow.add(flatButton("应用到时长", PANEL, TEXT, new Color(0x3C3F44)));
        ((JButton) inputRow.getComponent(inputRow.getComponentCount() - 1))
                .addActionListener(e -> applyCountdownInput());
        gc.gridy = 3;
        gc.insets = new Insets(16, 0, 0, 0);
        top.add(inputRow, gc);

        /* 预设 */
        JPanel presets = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        presets.setOpaque(false);
        int[][] ps = {{1, 0}, {3, 0}, {5, 0}, {10, 0}, {25, 0}, {60, 0}};
        for (int[] v : ps) {
            final int min = v[0];
            JButton b = flatButton(min >= 60 ? (min / 60) + " 小时" : min + " 分钟",
                    PANEL, TEXT, new Color(0x3C3F44));
            b.addActionListener(e -> {
                cdH.setText("00");
                cdM.setText(String.format("%02d", min));
                cdS.setText("00");
                applyCountdownInput();
            });
            presets.add(b);
        }
        gc.gridy = 4;
        gc.insets = new Insets(10, 0, 0, 0);
        top.add(presets, gc);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        btns.setOpaque(false);
        cdStartBtn = flatButton("开 始", ACCENT, Color.WHITE, ACCENT_HI);
        cdStartBtn.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 14));
        cdStartBtn.setBorder(BorderFactory.createEmptyBorder(9, 30, 9, 30));
        cdStartBtn.addActionListener(e -> toggleCountdown());
        JButton cdReset = flatButton("重 置", PANEL, TEXT, new Color(0x3C3F44));
        cdReset.addActionListener(e -> resetCountdown());
        btns.add(cdStartBtn);
        btns.add(cdReset);
        gc.gridy = 5;
        gc.insets = new Insets(18, 0, 0, 0);
        top.add(btns, gc);

        p.add(top, BorderLayout.NORTH);
        p.add(new JPanel() {{
            setOpaque(false);
        }}, BorderLayout.CENTER);
        return p;
    }

    static void applyCountdownInput() {
        long ms = parseHms(cdH.getText(), cdM.getText(), cdS.getText());
        if (ms <= 0) {
            setStatus("请输入大于 0 的倒计时时长", ERR);
            return;
        }
        cdTotalMs = ms;
        cdRemainMs = ms;
        cdRunning = false;
        cdStartBtn.setText("开 始");
        cdBar.setProgress(1.0);
        cdDisplay.setText(formatDuration(ms));
        cdHint.setText("已设置时长 " + formatDuration(ms) + "，点击开始");
        setStatus("倒计时时长已设置", OK);
    }

    static void toggleCountdown() {
        if (cdRunning) {
            cdRemainMs = Math.max(0, (cdEndNs - System.nanoTime()) / 1_000_000L);
            cdRunning = false;
            cdStartBtn.setText("继 续");
            cdHint.setText("已暂停，剩余 " + formatDuration(cdRemainMs));
            setStatus("倒计时已暂停", MUTED);
            return;
        }
        if (cdRemainMs <= 0) {
            applyCountdownInput();
            if (cdRemainMs <= 0) return;
        }
        cdEndNs = System.nanoTime() + cdRemainMs * 1_000_000L;
        cdRunning = true;
        cdStartBtn.setText("暂 停");
        cdHint.setText("倒计时进行中…");
        setStatus("倒计时已开始", OK);
    }

    static void resetCountdown() {
        cdRunning = false;
        cdRemainMs = cdTotalMs;
        cdStartBtn.setText("开 始");
        cdBar.setProgress(1.0);
        cdDisplay.setText(formatDuration(cdTotalMs));
        cdHint.setText(cdTotalMs > 0 ? "已重置为 " + formatDuration(cdTotalMs) : "设置时长后点击开始");
        setStatus("倒计时已重置", MUTED);
    }

    /* ==================== 卡片 4：闹钟与提醒 ==================== */
    static JPanel cardAlarm() {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        final JComboBox<String> typeBox = darkCombo(new String[]{"每日闹钟", "定时提醒"});
        typeBox.setPreferredSize(new Dimension(130, 32));
        final JTextField timeField = darkField("07:30", 10);
        final JTextField labelField = darkField("起床", 10);

        JPanel form = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
        form.setBackground(CARD);
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        form.add(mutedLabel("类型"));
        form.add(typeBox);
        form.add(mutedLabel("时间"));
        form.add(timeField);
        form.add(mutedLabel("标签"));
        form.add(labelField);
        JButton addBtn = flatButton("添加", ACCENT, Color.WHITE, ACCENT_HI);
        addBtn.addActionListener(e -> addAlarm(
                (String) typeBox.getSelectedItem(), timeField.getText().trim(), labelField.getText().trim()));
        form.add(addBtn);
        p.add(form, BorderLayout.NORTH);

        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.setBackground(BG);
        JLabel hint = new JLabel("每日闹钟填 HH:mm（如 07:30）；定时提醒填 yyyy-MM-dd HH:mm（如 "
                + LocalDate.now().plusDays(1) + " 15:00）");
        hint.setFont(SMALL);
        hint.setForeground(new Color(0x6B6F76));
        wrap.add(hint, BorderLayout.NORTH);

        alarmList = new JPanel();
        alarmList.setLayout(new BoxLayout(alarmList, BoxLayout.Y_AXIS));
        alarmList.setBackground(FIELD);
        JScrollPane sp = darkScroll(alarmList);
        sp.setBorder(BorderFactory.createLineBorder(BORDER));
        wrap.add(sp, BorderLayout.CENTER);
        p.add(wrap, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bottom.setOpaque(false);
        JButton testBtn = flatButton("测试提醒效果", PANEL, TEXT, new Color(0x3C3F44));
        testBtn.addActionListener(e -> fireAlert("测试提醒", "这是一条测试提醒，说明提醒功能工作正常。"));
        JButton cleanBtn = flatButton("清除已提醒", PANEL, TEXT, new Color(0x3C3F44));
        cleanBtn.addActionListener(e -> {
            alarms.removeIf(a -> !a.daily && a.fired);
            saveAlarms();
            refreshAlarms();
            setStatus("已清除过期的一次性提醒", MUTED);
        });
        bottom.add(testBtn);
        bottom.add(cleanBtn);
        p.add(bottom, BorderLayout.SOUTH);

        refreshAlarms();
        return p;
    }

    static JPanel alarmList;

    static void addAlarm(String type, String timeText, String label) {
        Alarm a = new Alarm();
        a.daily = "每日闹钟".equals(type);
        a.label = label == null || label.isEmpty() ? (a.daily ? "闹钟" : "提醒") : label;
        if (a.daily) {
            try {
                LocalTime t = parseTime(timeText);
                if (t == null) throw new IllegalArgumentException("bad time");
                a.hour = t.getHour();
                a.minute = t.getMinute();
            } catch (Exception ex) {
                setStatus("每日闹钟时间格式错误，请填 HH:mm，例如 07:30", ERR);
                return;
            }
        } else {
            try {
                LocalDateTime dt = parseDateTime(timeText);
                if (dt == null) throw new IllegalArgumentException("bad datetime");
                a.targetEpoch = dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (a.targetEpoch < System.currentTimeMillis()) {
                    setStatus("定时提醒的时间已经过去了，请填将来的时间", ERR);
                    return;
                }
            } catch (Exception ex) {
                setStatus("定时提醒格式错误，请填 yyyy-MM-dd HH:mm，例如 " + LocalDate.now().plusDays(1) + " 15:00", ERR);
                return;
            }
        }
        alarms.add(a);
        saveAlarms();
        refreshAlarms();
        setStatus("已添加：" + a.describe() + "（下次 " + a.nextText() + "）", OK);
    }

    static void refreshAlarms() {
        if (alarmList == null) return;
        alarmList.removeAll();
        if (alarms.isEmpty()) {
            JLabel l = new JLabel("暂无闹钟或提醒，使用上方表单添加");
            l.setFont(SMALL);
            l.setForeground(new Color(0x6B6F76));
            l.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            alarmList.add(row(l));
        } else {
            for (Alarm a : new ArrayList<>(alarms)) {
                alarmList.add(alarmRow(a));
            }
        }
        alarmList.revalidate();
        alarmList.repaint();
    }

    static JPanel alarmRow(final Alarm a) {
        JPanel r = new JPanel(new GridBagLayout());
        r.setBackground(FIELD);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        r.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x26282B)));

        DarkCheckBox cb = new DarkCheckBox("");
        cb.setSelected(a.enabled);
        cb.setToolTipText("启用 / 停用");
        cb.addActionListener(e -> {
            a.enabled = cb.isSelected();
            saveAlarms();
            refreshAlarms();
        });

        JLabel time = new JLabel(a.timeText());
        time.setFont(new Font("Consolas", Font.BOLD, 20));
        time.setForeground(a.enabled ? TEXT : new Color(0x5A5D63));
        time.setPreferredSize(new Dimension(120, 26));

        JLabel desc = new JLabel(a.typeText() + " · " + a.label
                + (a.enabled ? "   （下次 " + a.nextText() + "）" : "   （已停用）"));
        desc.setFont(SMALL);
        desc.setForeground(a.enabled ? MUTED : new Color(0x5A5D63));
        desc.setBorder(BorderFactory.createLineBorder(new Color(0x3A3D41), 0));

        JButton del = flatButton("\u2715", FIELD, MUTED, new Color(0x3C3F44));
        del.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 12));
        del.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        del.setToolTipText("删除");
        del.addActionListener(e -> {
            alarms.remove(a);
            saveAlarms();
            refreshAlarms();
            setStatus("已删除该闹钟", MUTED);
        });

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 14, 0, 6);
        gc.gridx = 0;
        r.add(cb, gc);
        gc.gridx = 1;
        r.add(time, gc);
        gc.gridx = 2;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        r.add(desc, gc);
        gc.gridx = 3;
        gc.weightx = 0;
        gc.insets = new Insets(0, 6, 0, 10);
        r.add(del, gc);
        return r;
    }

    /* ==================== 闹钟模型 ==================== */
    static class Alarm {
        boolean daily;
        int hour, minute;
        long targetEpoch;
        String label = "";
        boolean enabled = true;
        boolean fired;
        String lastFiredDay = "";

        String timeText() {
            if (daily) return String.format("%02d:%02d", hour, minute);
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(targetEpoch),
                    java.time.ZoneId.systemDefault()).format(DT_FMT);
        }

        String typeText() {
            return daily ? "每日闹钟" : "定时提醒";
        }

        String describe() {
            return typeText() + " " + timeText() + " " + label;
        }

        String nextText() {
            if (!enabled) return "已停用";
            long now = System.currentTimeMillis();
            if (daily) {
                LocalDateTime today = LocalDate.now().atTime(hour, minute);
                LocalDateTime next = today.atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli() > now ? today : today.plusDays(1);
                long mins = java.time.Duration.between(LocalDateTime.now(), next).toMinutes();
                return next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        + (mins < 60 * 24 ? "（" + humanGap(mins) + "后）" : "");
            }
            if (fired) return "已提醒";
            long mins = (targetEpoch - now) / 60000L;
            return timeText() + (mins >= 0 && mins < 60 * 24 * 30 ? "（" + humanGap(mins) + "后）" : "");
        }

        String serialize() {
            return (daily ? "D" : "O") + "|" + hour + "|" + minute + "|" + targetEpoch + "|"
                    + enabled + "|" + fired + "|" + lastFiredDay + "|"
                    + label.replace("|", "/").replace("\n", " ");
        }

        static Alarm parse(String s) {
            try {
                String[] f = s.split("\\|", -1);
                Alarm a = new Alarm();
                a.daily = "D".equals(f[0]);
                a.hour = Integer.parseInt(f[1]);
                a.minute = Integer.parseInt(f[2]);
                a.targetEpoch = Long.parseLong(f[3]);
                a.enabled = Boolean.parseBoolean(f[4]);
                a.fired = Boolean.parseBoolean(f[5]);
                a.lastFiredDay = f[6];
                a.label = f.length > 7 ? f[7] : "";
                return a;
            } catch (Exception e) {
                return null;
            }
        }
    }

    static String humanGap(long minutes) {
        if (minutes < 1) return "不到 1 分钟";
        if (minutes < 60) return minutes + " 分钟";
        long h = minutes / 60, m = minutes % 60;
        if (h < 24) return h + " 小时" + (m > 0 ? m + " 分钟" : "");
        long d = h / 24;
        return d + " 天" + (h % 24 > 0 ? (h % 24) + " 小时" : "");
    }

    static void loadAlarms() {
        String raw = prefs.get("alarms", "");
        alarms.clear();
        if (!raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                if (line.trim().isEmpty()) continue;
                Alarm a = Alarm.parse(line);
                if (a != null) alarms.add(a);
            }
        }
    }

    static void saveAlarms() {
        StringBuilder sb = new StringBuilder();
        for (Alarm a : alarms) {
            sb.append(a.serialize()).append('\n');
        }
        prefs.put("alarms", sb.toString());
    }

    /* ==================== 主循环 ==================== */
    static void tick() {
        LocalDateTime now = LocalDateTime.now();

        /* 时钟 */
        long sec = System.currentTimeMillis() / 1000;
        if (sec != lastSecond) {
            lastSecond = sec;
            LocalTime t = now.toLocalTime();
            if (clockTime != null) {
                clockTime.setTime(String.format("%02d:%02d", t.getHour(), t.getMinute()),
                        String.format("%02d", t.getSecond()));
            }
            if (clockDate != null) {
                clockDate.setText(now.format(DATE_FMT) + "  " + weekday(now));
            }
            if (analog != null) analog.repaint();
            checkAlarms(now);
        }

        /* 计时器 */
        if (swRunning && swDisplay != null) {
            swDisplay.setText(formatStopwatch(swElapsedNs()));
        }

        /* 倒计时 */
        if (cdRunning && cdDisplay != null) {
            long remain = (cdEndNs - System.nanoTime()) / 1_000_000L;
            if (remain <= 0) {
                cdRunning = false;
                cdRemainMs = 0;
                cdStartBtn.setText("开 始");
                cdDisplay.setText("00:00:00");
                cdBar.setProgress(0);
                cdHint.setText("倒计时结束");
                String msg = "设定的 " + formatDuration(cdTotalMs) + " 倒计时已完成。";
                setStatus("倒计时结束", ERR);
                fireAlert("倒计时结束", msg);
            } else {
                cdRemainMs = remain;
                cdDisplay.setText(formatDuration(remain));
                cdBar.setProgress(cdTotalMs <= 0 ? 0 : (double) remain / cdTotalMs);
                cdHint.setText("倒计时进行中…");
            }
        }

        /* 悬浮窗 */
        if (overlay != null && overlay.isVisible()) {
            overlay.refresh(now, buildOverlaySub());
        }
    }

    static String buildOverlaySub() {
        if (cdRunning) return "倒计时 " + formatDuration((cdEndNs - System.nanoTime()) / 1_000_000L);
        if (swRunning) return "计时 " + formatStopwatch(swElapsedNs());
        return LocalDate.now().format(DATE_FMT) + " " + weekday(LocalDateTime.now());
    }

    static String weekday(LocalDateTime dt) {
        String[] w = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return w[dt.getDayOfWeek().getValue() - 1];
    }

    /* ==================== 闹钟触发 ==================== */
    static void checkAlarms(LocalDateTime now) {
        String today = now.toLocalDate().toString();
        boolean changed = false;
        for (Alarm a : new ArrayList<>(alarms)) {
            if (!a.enabled) continue;
            if (a.daily) {
                if (now.getHour() == a.hour && now.getMinute() == a.minute && !today.equals(a.lastFiredDay)) {
                    a.lastFiredDay = today;
                    changed = true;
                    fireAlert("闹钟 · " + a.label,
                            String.format("现在是 %02d:%02d，%s时间到了。", a.hour, a.minute, a.label));
                }
            } else {
                if (!a.fired && System.currentTimeMillis() >= a.targetEpoch) {
                    a.fired = true;
                    a.enabled = false;
                    changed = true;
                    fireAlert("定时提醒 · " + a.label,
                            "你设定的提醒时间 " + a.timeText() + " 到了：\n" + a.label);
                }
            }
        }
        if (changed) {
            saveAlarms();
            refreshAlarms();
        }
    }

    /* ==================== 提醒窗口 ==================== */
    static void fireAlert(String title, String message) {
        SOUND.start();
        AlertWindow w = new AlertWindow(title, message, () -> snooze(title, message));
        w.setVisible(true);
        openAlerts.add(w);
        try {
            w.setLocationRelativeTo(null);
            w.toFront();
        } catch (Exception ignored) {
        }
    }

    static void snooze(String title, String message) {
        Alarm a = new Alarm();
        a.daily = false;
        a.label = "稍后提醒（" + title + "）";
        a.targetEpoch = System.currentTimeMillis() + 5 * 60 * 1000L;
        alarms.add(a);
        saveAlarms();
        refreshAlarms();
        setStatus("已设置 5 分钟后再提醒", MUTED);
    }

    static void closeAlert(AlertWindow w) {
        openAlerts.remove(w);
        w.dispose();
        if (openAlerts.isEmpty()) SOUND.stop();
    }

    static class AlertWindow extends JFrame {
        AlertWindow(String title, String message, Runnable onSnooze) {
            setUndecorated(true);
            setAlwaysOnTop(true);
            setTitle(title);
            setSize(440, 240);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(PANEL);
            root.setBorder(BorderFactory.createLineBorder(ACCENT, 2));
            setContentPane(root);

            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(TITLE_BG);
            bar.setPreferredSize(new Dimension(0, 38));
            JLabel t = new JLabel("  \u23F0  " + title);
            t.setFont(TITLE_F);
            t.setForeground(Color.WHITE);
            bar.add(t, BorderLayout.WEST);
            JButton x = new JButton("\u2715");
            x.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 13));
            x.setForeground(new Color(0xC8CBD0));
            x.setBackground(TITLE_BG);
            x.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
            x.setFocusPainted(false);
            x.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            x.addActionListener(e -> closeAlert(this));
            bar.add(x, BorderLayout.EAST);
            root.add(bar, BorderLayout.NORTH);

            JLabel body = new JLabel("<html><div style='width:380px'>" + message.replace("\n", "<br>") + "</div></html>");
            body.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 15));
            body.setForeground(TEXT);
            body.setBorder(BorderFactory.createEmptyBorder(18, 20, 10, 20));
            root.add(body, BorderLayout.CENTER);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            btns.setOpaque(false);
            btns.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
            JButton snooze = flatButton("稍后 5 分钟", PANEL, TEXT, new Color(0x3C3F44));
            snooze.addActionListener(e -> {
                if (onSnooze != null) onSnooze.run();
                closeAlert(this);
            });
            JButton ok = flatButton("我知道了", ACCENT, Color.WHITE, ACCENT_HI);
            ok.addActionListener(e -> closeAlert(this));
            btns.add(snooze);
            btns.add(ok);
            root.add(btns, BorderLayout.SOUTH);

            // 标题栏闪烁，增强提醒效果
            final javax.swing.Timer blink = new javax.swing.Timer(450, null);
            final boolean[] on = {true};
            blink.addActionListener(e -> {
                on[0] = !on[0];
                bar.setBackground(on[0] ? TITLE_BG : new Color(0x3A0C10));
            });
            blink.start();
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    blink.stop();
                }
            });
        }
    }

    /* ==================== 悬浮窗 ==================== */
    static void toggleOverlay() {
        if (overlay == null) return;
        if (overlay.isVisible()) {
            overlay.setVisible(false);
            overlayWanted = false;
            setStatus("悬浮窗已隐藏", MUTED);
        } else {
            overlay.setVisible(true);
            overlay.toFront();
            overlayWanted = true;
            setStatus("悬浮窗已显示", OK);
        }
    }

    static void showOverlay() {
        overlay = new OverlayClock();
        int x = prefs.getInt("ovX", Integer.MIN_VALUE);
        int y = prefs.getInt("ovY", Integer.MIN_VALUE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        if (x == Integer.MIN_VALUE) {
            x = scr.width - overlay.getWidth() - 40;
            y = 60;
        }
        overlay.setLocation(x, y);
        overlay.setVisible(true);
    }

    static class OverlayClock extends JFrame {
        final JLabel time = new JLabel("--:--:--");
        final JLabel sub = new JLabel(" ");
        final JPanel body;

        OverlayClock() {
            setUndecorated(true);
            setAlwaysOnTop(true);
            setSize(250, 104);

            boolean translucent = false;
            try {
                translucent = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
            } catch (Exception ignored) {
            }
            final boolean round = translucent;
            if (translucent) {
                setBackground(new Color(0, 0, 0, 0));
            }

            body = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(round ? new Color(16, 17, 19, 226) : FIELD);
                    if (round) {
                        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                        g2.setColor(new Color(0x3A3D41));
                        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                    } else {
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    }
                    g2.dispose();
                }
            };
            body.setOpaque(!round);
            if (!round) body.setBackground(FIELD);
            body.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            setContentPane(body);

            time.setFont(new Font("Consolas", Font.BOLD, 34));
            time.setForeground(TEXT);
            sub.setFont(SMALL);
            sub.setForeground(MUTED);
            sub.setHorizontalAlignment(SwingConstants.LEFT);

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            time.setAlignmentX(Component.LEFT_ALIGNMENT);
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);
            center.add(time);
            center.add(sub);
            body.add(center, BorderLayout.CENTER);

            JButton close = new JButton("\u2715");
            close.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
            close.setForeground(new Color(0x8A8F96));
            close.setBackground(new Color(0x1A1B1D));
            close.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
            close.setFocusPainted(false);
            close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            close.setToolTipText("隐藏悬浮窗");
            close.addActionListener(e -> toggleOverlay());
            JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            topRight.setOpaque(false);
            topRight.add(close);
            body.add(topRight, BorderLayout.EAST);

            final Point[] press = {null};
            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    press[0] = e.getPoint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    prefs.putInt("ovX", getLocation().x);
                    prefs.putInt("ovY", getLocation().y);
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        frame.setVisible(true);
                        frame.setState(Frame.NORMAL);
                        frame.toFront();
                    }
                }
            };
            MouseMotionAdapter move = new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (press[0] == null) return;
                    Point loc = getLocation();
                    setLocation(loc.x + e.getX() - press[0].x, loc.y + e.getY() - press[0].y);
                }
            };
            body.addMouseListener(drag);
            body.addMouseMotionListener(move);
            time.addMouseListener(drag);
            time.addMouseMotionListener(move);
            sub.addMouseListener(drag);
            sub.addMouseMotionListener(move);

            final JPopupMenu menu = darkMenu();
            JMenuItem showItem = new JMenuItem("显示主窗口");
            showItem.setFont(UI);
            showItem.setForeground(TEXT);
            showItem.setBackground(PANEL);
            showItem.addActionListener(e -> {
                frame.setVisible(true);
                frame.setState(Frame.NORMAL);
                frame.toFront();
            });
            JMenuItem hideItem = new JMenuItem("隐藏悬浮窗");
            hideItem.setFont(UI);
            hideItem.setForeground(TEXT);
            hideItem.setBackground(PANEL);
            hideItem.addActionListener(e -> toggleOverlay());
            menu.add(showItem);
            menu.add(hideItem);

            MouseAdapter popup = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    maybeShow(e);
                }
                @Override public void mouseReleased(MouseEvent e) {
                    maybeShow(e);
                }
                private void maybeShow(MouseEvent e) {
                    if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY());
                }
            };
            body.addMouseListener(popup);
        }

        void refresh(LocalDateTime now, String subText) {
            String t = now.format(TIME_FMT);
            if (!t.equals(time.getText())) time.setText(t);
            if (!subText.equals(sub.getText())) sub.setText(subText);
        }
    }

    /* ==================== 托盘 ==================== */
    static void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu pm = new PopupMenu();
            MenuItem showItem = new MenuItem("显示主窗口");
            showItem.addActionListener(e -> {
                frame.setVisible(true);
                frame.setState(Frame.NORMAL);
                frame.toFront();
            });
            MenuItem ovItem = new MenuItem("显示 / 隐藏悬浮窗");
            ovItem.addActionListener(e -> toggleOverlay());
            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> exitApp());
            pm.add(showItem);
            pm.add(ovItem);
            pm.addSeparator();
            pm.add(exitItem);
            trayIcon = new TrayIcon(appIcon(32), "Chrono 时钟", pm);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> {
                frame.setVisible(true);
                frame.setState(Frame.NORMAL);
                frame.toFront();
            });
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ignored) {
            trayIcon = null;
        }
    }

    static Image appIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x161719));
        g.fillRoundRect(0, 0, size, size, size / 4, size / 4);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(Math.max(1.5f, size / 14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawOval(size / 8, size / 8, size * 3 / 4, size * 3 / 4);
        g.drawLine(size / 2, size / 2, size / 2, size * 3 / 10);
        g.drawLine(size / 2, size / 2, size * 7 / 10, size / 2);
        g.dispose();
        return img;
    }

    /* ==================== 提示音 ==================== */
    static class AlarmSound {
        private volatile boolean playing;
        private Thread thread;
        private volatile SourceDataLine line;

        void start() {
            if (playing) return;
            playing = true;
            thread = new Thread(this::loop, "chrono-alarm");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            playing = false;
            SourceDataLine l = line;
            if (l != null) {
                try {
                    l.stop();
                    l.flush();
                    l.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void loop() {
            try {
                AudioFormat fmt = new AudioFormat(44100f, 16, 1, true, false);
                SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
                line = l;
                l.open(fmt);
                l.start();
                byte[] beep = tone(880, 170);
                byte[] low = tone(660, 170);
                byte[] gap = new byte[(int) (44100 * 0.16) * 2];
                while (playing) {
                    l.write(beep, 0, beep.length);
                    l.write(gap, 0, gap.length);
                    l.write(low, 0, low.length);
                    l.write(gap, 0, gap.length);
                    if (!playing) break;
                    l.write(beep, 0, beep.length);
                    l.write(gap, 0, gap.length);
                    for (int i = 0; i < 6 && playing; i++) {
                        Thread.sleep(120);
                    }
                }
                l.drain();
                l.stop();
                l.close();
            } catch (Exception e) {
                // 没有可用音频设备时退化为系统蜂鸣
                while (playing) {
                    Toolkit.getDefaultToolkit().beep();
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }

        private static byte[] tone(double hz, int ms) {
            int n = (int) (44100 * ms / 1000.0);
            byte[] out = new byte[n * 2];
            int fade = Math.max(1, n / 8);
            for (int i = 0; i < n; i++) {
                double env = Math.min(1.0, Math.min(i, n - i) / (double) fade);
                short v = (short) (Math.sin(2 * Math.PI * hz * i / 44100.0) * 0.42 * env * Short.MAX_VALUE);
                out[i * 2] = (byte) (v & 0xFF);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            }
            return out;
        }
    }

    /* ==================== 通用暗色控件 ==================== */
    static JLabel mutedLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(SMALL);
        l.setForeground(MUTED);
        return l;
    }

    static JLabel monoLabel(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }

    static JButton flatButton(String text, Color bg, Color fg, Color hover) {
        final JButton b = new JButton(text);
        b.setFont(UI);
        b.setForeground(fg);
        b.setBackground(bg);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (b.isEnabled()) b.setBackground(hover);
            }
            @Override public void mouseExited(MouseEvent e) {
                b.setBackground(bg);
            }
        });
        return b;
    }

    static JTextField darkField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setFont(UI);
        f.setBackground(FIELD);
        f.setForeground(TEXT);
        f.setCaretColor(new Color(0x8AB4F8));
        f.setSelectionColor(ACCENT);
        f.setSelectedTextColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        return f;
    }

    static JComboBox<String> darkCombo(String[] items) {
        final JComboBox<String> cb = new JComboBox<>(items);
        cb.setFont(UI);
        cb.setBackground(FIELD);
        cb.setForeground(TEXT);
        cb.setFocusable(false);
        cb.setOpaque(true);
        cb.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(3, 8, 3, 4)));
        cb.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton b = new JButton("\u25BE");
                b.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 10));
                b.setForeground(MUTED);
                b.setBackground(FIELD);
                b.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 8));
                b.setContentAreaFilled(false);
                b.setFocusable(false);
                return b;
            }
            @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(FIELD);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        });
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                l.setFont(UI);
                l.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
                l.setOpaque(true);
                l.setBackground(selected ? ACCENT : FIELD);
                l.setForeground(selected ? Color.WHITE : TEXT);
                return l;
            }
        });
        return cb;
    }

    static JScrollPane darkScroll(JComponent content) {
        JScrollPane sp = new JScrollPane(content);
        sp.setBackground(FIELD);
        sp.setBorder(BorderFactory.createLineBorder(BORDER));
        sp.getViewport().setBackground(FIELD);
        sp.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        sp.getHorizontalScrollBar().setUI(new DarkScrollBarUI());
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getVerticalScrollBar().setBackground(FIELD);
        return sp;
    }

    static class DarkScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = new Color(0x4A4D52);
            trackColor = FIELD;
        }
        @Override protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }
        @Override protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }
        private JButton zeroButton() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() ? new Color(0x6B6F76) : thumbColor);
            g2.fillRoundRect(r.x + 2, r.y + 2, Math.max(1, r.width - 4), Math.max(1, r.height - 4), 10, 10);
            g2.dispose();
        }
    }

    static class DarkCheckBox extends JCheckBox {
        DarkCheckBox(String text) {
            super(text);
            setOpaque(false);
            setForeground(TEXT);
            setFont(UI);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setIcon(box(false));
            setSelectedIcon(box(true));
        }

        static Icon box(final boolean selected) {
            return new Icon() {
                @Override public int getIconWidth() {
                    return 18;
                }
                @Override public int getIconHeight() {
                    return 18;
                }
                @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(selected ? ACCENT : new Color(0x161719));
                    g2.fillRoundRect(x, y, 18, 18, 5, 5);
                    g2.setColor(selected ? ACCENT : BORDER);
                    g2.drawRoundRect(x, y, 17, 17, 5, 5);
                    if (selected) {
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawPolyline(new int[]{x + 5, x + 8, x + 13}, new int[]{y + 9, y + 12, y + 5}, 3);
                    }
                    g2.dispose();
                }
            };
        }
    }

    /** 进度条（倒计时剩余比例） */
    static class Bar extends JComponent {
        private double progress = 1.0;

        void setProgress(double p) {
            progress = Math.max(0, Math.min(1, p));
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight(), w = getWidth();
            g2.setColor(FIELD);
            g2.fillRoundRect(0, 0, w, h, h, h);
            int fw = (int) (w * progress);
            if (fw > 0) {
                g2.setColor(progress > 0.25 ? ACCENT : ERR);
                g2.fillRoundRect(0, 0, Math.max(fw, h), h, h, h);
            }
            g2.dispose();
        }
    }

    /** 大号数字时间（分钟白色、秒蓝色） */
    static class BigTime extends JComponent {
        private String hm = "00:00";
        private String sec = "00";
        private final Font f = new Font("Consolas", Font.BOLD, 58);

        void setTime(String a, String b) {
            if (!a.equals(hm) || !b.equals(sec)) {
                hm = a;
                sec = b;
                repaint();
            }
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(f);
            return new Dimension(fm.stringWidth("00:00:00") + 10, fm.getHeight() + 6);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            int total = fm.stringWidth("00:00:00");
            int x = (getWidth() - total) / 2;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(TEXT);
            g2.drawString(hm, x, y);
            x += fm.stringWidth(hm);
            g2.drawString(":", x, y);
            x += fm.stringWidth(":");
            g2.setColor(ACCENT);
            g2.drawString(sec, x, y);
            g2.dispose();
        }
    }

    /** 模拟表盘 */
    static class AnalogClock extends JComponent {
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()) - 8;
            int cx = getWidth() / 2, cy = getHeight() / 2;
            int r = size / 2;

            g2.setColor(FIELD);
            g2.fillOval(cx - r, cy - r, size, size);
            g2.setColor(BORDER);
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(cx - r, cy - r, size, size);

            for (int i = 0; i < 60; i++) {
                double ang = Math.toRadians(i * 6 - 90);
                boolean major = i % 5 == 0;
                int len = major ? r / 9 : r / 18;
                int x1 = cx + (int) ((r - 6) * Math.cos(ang));
                int y1 = cy + (int) ((r - 6) * Math.sin(ang));
                int x2 = cx + (int) ((r - 6 - len) * Math.cos(ang));
                int y2 = cy + (int) ((r - 6 - len) * Math.sin(ang));
                g2.setColor(major ? new Color(0x9AA0A6) : new Color(0x4A4D52));
                g2.setStroke(new BasicStroke(major ? 2.2f : 1f));
                g2.drawLine(x1, y1, x2, y2);
            }

            LocalTime now = LocalTime.now();
            double secAng = Math.toRadians(now.getSecond() * 6 - 90);
            double minAng = Math.toRadians(now.getMinute() * 6 + now.getSecond() * 0.1 - 90);
            double hourAng = Math.toRadians((now.getHour() % 12) * 30 + now.getMinute() * 0.5 - 90);

            g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(TEXT);
            g2.drawLine(cx, cy, cx + (int) (r * 0.50 * Math.cos(hourAng)), cy + (int) (r * 0.50 * Math.sin(hourAng)));
            g2.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0xC8CBD0));
            g2.drawLine(cx, cy, cx + (int) (r * 0.74 * Math.cos(minAng)), cy + (int) (r * 0.74 * Math.sin(minAng)));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(ACCENT);
            g2.drawLine(cx, cy, cx + (int) (r * 0.84 * Math.cos(secAng)), cy + (int) (r * 0.84 * Math.sin(secAng)));

            g2.setColor(ACCENT);
            g2.fillOval(cx - 5, cy - 5, 10, 10);
            g2.dispose();
        }
    }

    /* ==================== 工具方法 ==================== */
    static String formatStopwatch(long nanos) {
        long totalMs = nanos / 1_000_000L;
        long cs = (totalMs / 10) % 100;
        long s = (totalMs / 1000) % 60;
        long m = (totalMs / 60000) % 60;
        long h = totalMs / 3600000;
        return String.format("%02d:%02d:%02d.%02d", h, m, s, cs);
    }

    static String formatDuration(long millis) {
        long total = Math.max(0, millis) / 1000;
        return String.format("%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    static long parseHms(String h, String m, String s) {
        try {
            long hh = h == null || h.trim().isEmpty() ? 0 : Long.parseLong(h.trim());
            long mm = m == null || m.trim().isEmpty() ? 0 : Long.parseLong(m.trim());
            long ss = s == null || s.trim().isEmpty() ? 0 : Long.parseLong(s.trim());
            if (hh < 0 || mm < 0 || ss < 0) return -1;
            return ((hh * 60 + mm) * 60 + ss) * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 宽松解析时间：支持 7:30 / 07:30 / 730 / 0730 等写法 */
    static LocalTime parseTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace('\uFF1A', ':').replace('\u3002', '.');
        if (s.isEmpty()) return null;
        if (s.matches("\\d{3,4}")) {
            s = s.length() == 3
                    ? "0" + s.charAt(0) + ":" + s.substring(1)
                    : s.substring(0, 2) + ":" + s.substring(2);
        }
        String[] patterns = {"H:mm", "HH:mm", "H:m", "HH:m", "H:mm:ss", "HH:mm:ss"};
        for (String p : patterns) {
            try {
                return LocalTime.parse(s, DateTimeFormatter.ofPattern(p));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 宽松解析“日期 + 时间”：支持 - / . 分隔以及 T 分隔 */
    static LocalDateTime parseDateTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace('\uFF1A', ':').replace('/', '-')
                .replace('.', '-').replace('T', ' ').replace('t', ' ');
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        String[] patterns = {
                "yyyy-M-d H:m", "yyyy-M-d H:mm", "yyyy-MM-dd HH:mm",
                "yyyy-M-d HH:mm", "yyyy-MM-dd H:m", "yyyy-M-d H:m:s", "yyyy-MM-dd HH:mm:ss"
        };
        for (String p : patterns) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 自动换行的流式布局：工具栏控件放不下时整组换行，避免互相重叠 */
    static class WrapLayout extends FlowLayout {

        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override public Dimension minimumLayoutSize(Container target) {
            Dimension d = layoutSize(target, false);
            d.width -= (getHgap() + 1);
            return d;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;
                Insets insets = target.getInsets();
                int hgap = getHgap(), vgap = getVgap();
                int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);
                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0, rowHeight = 0;
                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowWidth + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth != 0) rowWidth += hgap;
                    rowWidth += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                }
                addRow(dim, rowWidth, rowHeight);
                dim.width += insets.left + insets.right + hgap * 2;
                dim.height += insets.top + insets.bottom + vgap * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) dim.height += getVgap();
            dim.height += rowHeight;
        }

        @Override public void layoutContainer(Container target) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int hgap = getHgap(), vgap = getVgap();
                int left = insets.left + hgap;
                int maxWidth = target.getWidth() - insets.left - insets.right - hgap * 2;
                int y = insets.top + vgap;
                int x = left, rowHeight = 0;
                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) continue;
                    int w = m.getPreferredSize().width;
                    int h = m.getPreferredSize().height;
                    if (x > left && x + w > left + maxWidth) {
                        x = left;
                        y += rowHeight + vgap;
                        rowHeight = 0;
                    }
                    m.setBounds(x, y, Math.min(w, maxWidth), h);
                    x += w + hgap;
                    rowHeight = Math.max(rowHeight, h);
                }
            }
        }
    }
}
