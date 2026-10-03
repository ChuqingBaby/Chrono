// Chrono 单文件启动器：把内嵌的运行时压缩包解压到用户目录后启动时钟（仅首次运行需要解压）
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Windows.Forms;

static class ChronoLauncher
{
    const string AppVersion = "1.0.0";

    [STAThread]
    static void Main()
    {
        string baseDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ChronoApp");
        string target = Path.Combine(baseDir, AppVersion);
        string exe = Path.Combine(target, "Chrono", "Chrono.exe");
        string marker = Path.Combine(target, ".ready");

        if (!File.Exists(exe) || !File.Exists(marker))
        {
            Splash splash = new Splash();
            splash.Show();
            splash.Refresh();
            try
            {
                if (Directory.Exists(target))
                {
                    try { Directory.Delete(target, true); } catch { }
                }
                Directory.CreateDirectory(target);

                string zipPath = Path.Combine(Path.GetTempPath(),
                    "chrono_" + Guid.NewGuid().ToString("N") + ".zip");
                using (Stream res = Assembly.GetExecutingAssembly()
                           .GetManifestResourceStream("payload.zip"))
                {
                    if (res == null) throw new Exception("内置资源缺失");
                    using (FileStream fs = File.Create(zipPath)) res.CopyTo(fs);
                }
                ZipFile.ExtractToDirectory(zipPath, target);
                try { File.Delete(zipPath); } catch { }
                File.WriteAllText(marker, AppVersion);
            }
            catch (Exception ex)
            {
                MessageBox.Show("首次初始化失败：" + ex.Message, "Chrono 时钟",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            finally
            {
                splash.Close();
            }
        }

        Process.Start(new ProcessStartInfo(exe) { WorkingDirectory = Path.GetDirectoryName(exe) });
    }

    class Splash : Form
    {
        public Splash()
        {
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(10, 10, 10);
            Size = new Size(320, 90);
            ShowInTaskbar = false;
            TopMost = true;
            Label l = new Label();
            l.Text = "正在初始化 Chrono 时钟（仅首次）…";
            l.ForeColor = Color.FromArgb(232, 234, 237);
            l.Font = new Font("Microsoft YaHei UI", 10F);
            l.Dock = DockStyle.Fill;
            l.TextAlign = ContentAlignment.MiddleCenter;
            Controls.Add(l);
        }
    }
}
