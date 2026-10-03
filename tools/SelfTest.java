import java.time.LocalDateTime;
import java.time.LocalTime;

/** 自检：不依赖 GUI，直接验证核心逻辑 */
public class SelfTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name + "  " + detail);
        } else {
            fail++;
            System.out.println("  [失败] " + name + "  " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== 1. 计时器格式化 =====");
        check("0 纳秒", "00:00:00.00".equals(Main.formatStopwatch(0)), Main.formatStopwatch(0));
        check("1 秒 50 厘秒", "00:00:01.50".equals(Main.formatStopwatch(1_500_000_000L)),
                Main.formatStopwatch(1_500_000_000L));
        check("1 小时 2 分 3 秒", "01:02:03.00".equals(Main.formatStopwatch(3_723_000_000_000L)),
                Main.formatStopwatch(3_723_000_000_000L));

        System.out.println("===== 2. 时长格式化 =====");
        check("0", "00:00:00".equals(Main.formatDuration(0)), Main.formatDuration(0));
        check("90 秒", "00:01:30".equals(Main.formatDuration(90_000)), Main.formatDuration(90_000));
        check("负值归零", "00:00:00".equals(Main.formatDuration(-5)), Main.formatDuration(-5));

        System.out.println("===== 3. 时/分/秒解析 =====");
        check("1时2分3秒", Main.parseHms("1", "2", "3") == 3_723_000L, String.valueOf(Main.parseHms("1", "2", "3")));
        check("空值视为 0", Main.parseHms("", "", "") == 0, String.valueOf(Main.parseHms("", "", "")));
        check("非法输入返回 -1", Main.parseHms("abc", "0", "0") == -1, String.valueOf(Main.parseHms("abc", "0", "0")));
        check("负数返回 -1", Main.parseHms("-1", "0", "0") == -1, String.valueOf(Main.parseHms("-1", "0", "0")));

        System.out.println("===== 4. 时间解析（宽松） =====");
        check("07:30", LocalTime.of(7, 30).equals(Main.parseTime("07:30")), String.valueOf(Main.parseTime("07:30")));
        check("7:30", LocalTime.of(7, 30).equals(Main.parseTime("7:30")), String.valueOf(Main.parseTime("7:30")));
        check("730", LocalTime.of(7, 30).equals(Main.parseTime("730")), String.valueOf(Main.parseTime("730")));
        check("0730", LocalTime.of(7, 30).equals(Main.parseTime("0730")), String.valueOf(Main.parseTime("0730")));
        check("中文冒号 7：30", LocalTime.of(7, 30).equals(Main.parseTime("7：30")), String.valueOf(Main.parseTime("7：30")));
        check("空串返回 null", Main.parseTime("") == null, String.valueOf(Main.parseTime("")));
        check("乱填返回 null", Main.parseTime("abc") == null, String.valueOf(Main.parseTime("abc")));

        System.out.println("===== 5. 日期时间解析（宽松） =====");
        check("2026-10-01 15:00",
                LocalDateTime.of(2026, 10, 1, 15, 0).equals(Main.parseDateTime("2026-10-01 15:00")),
                String.valueOf(Main.parseDateTime("2026-10-01 15:00")));
        check("2026-10-01 9:05",
                LocalDateTime.of(2026, 10, 1, 9, 5).equals(Main.parseDateTime("2026-10-01 9:05")),
                String.valueOf(Main.parseDateTime("2026-10-01 9:05")));
        check("带 T 分隔",
                LocalDateTime.of(2026, 10, 1, 15, 0).equals(Main.parseDateTime("2026-10-01T15:00")),
                String.valueOf(Main.parseDateTime("2026-10-01T15:00")));
        check("斜杠分隔",
                LocalDateTime.of(2026, 10, 1, 15, 0).equals(Main.parseDateTime("2026/10/01 15:00")),
                String.valueOf(Main.parseDateTime("2026/10/01 15:00")));
        check("非法返回 null", Main.parseDateTime("下午三点") == null, String.valueOf(Main.parseDateTime("下午三点")));

        System.out.println("===== 6. 星期显示 =====");
        check("2026-10-01 是星期四",
                "星期四".equals(Main.weekday(LocalDateTime.of(2026, 10, 1, 9, 0))),
                Main.weekday(LocalDateTime.of(2026, 10, 1, 9, 0)));

        System.out.println("===== 7. 闹钟描述与下次时间 =====");
        Main.Alarm daily = new Main.Alarm();
        daily.daily = true;
        daily.hour = 7;
        daily.minute = 30;
        daily.label = "起床";
        check("每日闹钟时间文本", "07:30".equals(daily.timeText()), daily.timeText());
        check("每日闹钟类型", "每日闹钟".equals(daily.typeText()), daily.typeText());
        check("下次时间非空", daily.nextText() != null && daily.nextText().length() > 4, daily.nextText());
        check("停用后显示已停用", "已停用".equals(stopDisabled(daily)), stopDisabled(daily));

        Main.Alarm once = new Main.Alarm();
        once.daily = false;
        once.targetEpoch = System.currentTimeMillis() + 3_600_000L;
        once.label = "开会";
        check("定时提醒时间文本含日期", once.timeText().length() >= 16, once.timeText());
        check("定时提醒类型", "定时提醒".equals(once.typeText()), once.typeText());

        System.out.println("===== 8. 闹钟序列化 / 反序列化 =====");
        String line = daily.serialize();
        Main.Alarm back = Main.Alarm.parse(line);
        boolean same = back != null && back.daily == daily.daily && back.hour == daily.hour
                && back.minute == daily.minute && back.label.equals(daily.label);
        check("每日闹钟往返一致", same, line);
        String line2 = once.serialize();
        Main.Alarm back2 = Main.Alarm.parse(line2);
        check("定时提醒往返一致",
                back2 != null && back2.targetEpoch == once.targetEpoch && back2.label.equals(once.label), line2);
        check("损坏数据返回 null", Main.Alarm.parse("垃圾数据") == null, "垃圾数据");
        Main.Alarm special = new Main.Alarm();
        special.daily = false;
        special.label = "含|竖线|的标签";
        check("标签中的竖线被转义",
                Main.Alarm.parse(special.serialize()) != null
                        && Main.Alarm.parse(special.serialize()).label.indexOf('|') < 0,
                special.serialize());

        System.out.println("===== 9. 时间差描述 =====");
        check("30 分钟", "30 分钟".equals(Main.humanGap(30)), Main.humanGap(30));
        check("90 分钟 → 1 小时30 分钟", "1 小时30 分钟".equals(Main.humanGap(90)), Main.humanGap(90));
        check("2 天", "2 天".equals(Main.humanGap(2 * 24 * 60)), Main.humanGap(2 * 24 * 60));

        System.out.println();
        System.out.println("================ 结果 ================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println(fail == 0 ? "全部通过 ✔" : "存在失败项 ✘");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String stopDisabled(Main.Alarm a) {
        boolean old = a.enabled;
        a.enabled = false;
        String s = a.nextText();
        a.enabled = old;
        return s;
    }
}
