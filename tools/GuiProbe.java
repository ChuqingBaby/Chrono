import javax.swing.*;

/** 界面探针：驱动真实界面，验证各卡片布局与倒计时触发提醒 */
public class GuiProbe {

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(Main::start);
        Thread.sleep(2500);

        System.out.println("===== A. 窗口与标题栏 =====");
        System.out.println("  窗口尺寸 = " + Main.frame.getWidth() + "x" + Main.frame.getHeight());
        System.out.println("  窗口标题 = " + Main.frame.getTitle());
        System.out.println("  标题栏背景 = " + toHex(Main.TITLE_BG) + "  (纯黑应为 #0A0A0A)");

        System.out.println("===== B. 左侧导航与卡片切换 =====");
        for (int i = 0; i < Main.CARD_NAMES.length; i++) {
            final String card = Main.CARD_NAMES[i];
            SwingUtilities.invokeAndWait(() -> {
                Main.cardLayout.show(Main.cards, card);
                Main.highlightTab(card);
            });
            Thread.sleep(400);
            final int idx = i;
            SwingUtilities.invokeAndWait(() -> {
                java.awt.Component comp = Main.cards.getComponent(idx);
                System.out.println("  卡片[" + Main.CARD_TITLES[idx] + "] 可见组件数 = "
                        + (comp instanceof java.awt.Container
                        ? ((java.awt.Container) comp).getComponentCount() : 0));
            });
        }

        System.out.println("===== C. 计时器 =====");
        SwingUtilities.invokeAndWait(() -> {
            Main.toggleStopwatch();
        });
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(() -> Main.addLap());// 第 1 次计次
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> Main.addLap());// 第 2 次计次
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> {
            System.out.println("  计时显示 = " + Main.swDisplay.getText());
            System.out.println("  已记录分段数 = " + Main.laps.size());
            System.out.println("  第1段 = " + Main.formatStopwatch(Main.laps.get(0)[0]));
            System.out.println("  第2段 = " + Main.formatStopwatch(Main.laps.get(1)[0]));
        });
        SwingUtilities.invokeAndWait(Main::resetStopwatch);
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> System.out.println("  重置后 = " + Main.swDisplay.getText()
                + " ，分段数 = " + Main.laps.size()));

        System.out.println("===== D. 倒计时（设为 3 秒，验证自动触发提醒） =====");
        SwingUtilities.invokeAndWait(() -> {
            Main.cdH.setText("00");
            Main.cdM.setText("00");
            Main.cdS.setText("03");
            Main.applyCountdownInput();
            System.out.println("  设定后显示 = " + Main.cdDisplay.getText() + " / 提示：" + Main.cdHint.getText());
        });
        SwingUtilities.invokeAndWait(Main::toggleCountdown);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(600);
            final int step = i;
            SwingUtilities.invokeAndWait(() -> System.out.println("  第 " + (step + 1) + " 次采样：剩余 "
                    + Main.cdDisplay.getText() + "，进度 " + String.format("%.2f", progressOf())));
        }
        Thread.sleep(1500);
        final boolean[] alertShown = {false};
        SwingUtilities.invokeAndWait(() -> {
            alertShown[0] = !Main.openAlerts.isEmpty();
            System.out.println("  倒计时结束提示 = " + Main.cdHint.getText());
            System.out.println("  提醒窗口已弹出 = " + alertShown[0]
                    + (alertShown[0] ? "（标题：" + Main.openAlerts.get(0).getTitle() + "）" : ""));
        });

        System.out.println("===== E. 定时提醒（1 秒后触发） =====");
        SwingUtilities.invokeAndWait(() -> {
            Main.Alarm a = new Main.Alarm();
            a.daily = false;
            a.label = "自动测试提醒";
            a.targetEpoch = System.currentTimeMillis() + 1000;
            Main.alarms.add(a);
            Main.refreshAlarms();
            System.out.println("  已添加一次性提醒，当前提醒窗口数 = " + Main.openAlerts.size());
        });
        Thread.sleep(2600);
        SwingUtilities.invokeAndWait(() -> System.out.println("  1 秒后提醒窗口数 = " + Main.openAlerts.size()
                + (Main.openAlerts.size() > 0
                ? "（最新标题：" + Main.openAlerts.get(Main.openAlerts.size() - 1).getTitle() + "）" : "")));

        System.out.println("===== F. 每日闹钟 =====");
        SwingUtilities.invokeAndWait(() -> {
            java.time.LocalTime soon = java.time.LocalTime.now().plusMinutes(1);
            Main.Alarm d = new Main.Alarm();
            d.daily = true;
            d.hour = soon.getHour();
            d.minute = soon.getMinute();
            d.label = "每日测试";
            Main.alarms.add(d);
            Main.refreshAlarms();
            System.out.println("  已添加每日闹钟 " + d.timeText() + " ，下次：" + d.nextText());
            System.out.println("  当前列表条数 = " + Main.alarms.size());
        });

        System.out.println("===== G. 悬浮窗 =====");
        SwingUtilities.invokeAndWait(() -> {
            System.out.println("  悬浮窗存在 = " + (Main.overlay != null));
            System.out.println("  悬浮窗可见 = " + (Main.overlay != null && Main.overlay.isVisible()));
            System.out.println("  悬浮窗尺寸 = " + (Main.overlay != null
                    ? Main.overlay.getWidth() + "x" + Main.overlay.getHeight() : "-"));
            System.out.println("  悬浮窗位置 = " + (Main.overlay != null ? Main.overlay.getLocation().toString() : "-"));
        });
        Thread.sleep(1200);
        final String[] ovTime = {""};
        SwingUtilities.invokeAndWait(() -> {
            Main.toggleOverlay();
            ovTime[0] = Main.overlay.isVisible() ? "显示" : "隐藏";
        });
        System.out.println("  切换后状态 = " + ovTime[0]);
        SwingUtilities.invokeAndWait(Main::toggleOverlay);

        System.out.println("===== H. 声音提醒 =====");
        Main.SOUND.start();
        Thread.sleep(900);
        Main.SOUND.stop();
        Thread.sleep(300);
        System.out.println("  提示音已播放并停止（未抛异常即正常）");

        System.out.println();
        System.out.println("界面探针执行完毕");
        System.exit(0);
    }

    static double progressOf() {
        return Main.cdTotalMs <= 0 ? 0 : (double) Main.cdRemainMs / Main.cdTotalMs;
    }

    static String toHex(java.awt.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
