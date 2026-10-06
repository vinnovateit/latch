using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace LatchSetup
{
    public class SetupForm : Form
    {
        private const int WM_NCLBUTTONDOWN = 0xA1;
        private const int HTCAPTION = 0x2;

        [DllImport("user32.dll")]
        private static extern bool ReleaseCapture();

        [DllImport("user32.dll")]
        private static extern int SendMessage(IntPtr hWnd, int Msg, int wParam, int lParam);

        [DllImport("gdi32.dll")]
        private static extern IntPtr CreateRoundRectRgn(int nLeftRect, int nTopRect, int nRightRect, int nBottomRect, int nWidthEllipse, int nHeightEllipse);

        [DllImport("gdi32.dll")]
        private static extern bool DeleteObject(IntPtr hObject);

        private readonly string[] args;
        private InstalledProduct installed;
        private ReleaseInfo release;

        // Form dimension constants (868x532)
        private const int NormalWidth = 868;
        private const int NormalHeight = 532;
        private const int ExpandedHeight = 700;

        // Custom UI Controls
        private WindowControlButton btnMin;
        private WindowControlButton btnClose;

        private LatchAnimationControl animControl;
        private Label lblStatus;
        private CustomProgressBar progressBar;
        private Label lblProgressPercent;
        private TextBox txtLogTrace;

        private Panel buttonPanel;
        private ModernButton btnPrimary;
        private ModernButton btnSecondary;
        private ModernButton btnDanger;

        public SetupForm(string[] commandLineArgs)
        {
            this.args = commandLineArgs ?? new string[0];
            InitializeComponent();
        }

        private void InitializeComponent()
        {
            this.Text = "Setup";
            this.FormBorderStyle = FormBorderStyle.None;
            this.StartPosition = FormStartPosition.CenterScreen;
            this.Size = new Size(NormalWidth, NormalHeight);
            this.BackColor = Theme.Bg; // Dark modern surface #18181b
            this.ForeColor = Theme.Text;
            this.Font = new Font("Segoe UI", 10.0f, FontStyle.Regular);
            this.DoubleBuffered = true;

            // Load application icon: extract from executable, fallback to Windows default setup/application icon
            try
            {
                this.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            }
            catch { }

            if (this.Icon == null)
            {
                this.Icon = SystemIcons.Application;
            }

            // Window Control Buttons (flush to the top-right edge, no container)
            btnMin = new WindowControlButton
            {
                Text = "—",
                Font = new Font("Segoe UI", 10.0f, FontStyle.Regular),
                Location = new Point(this.Width - 96, 0),
                Size = new Size(48, 32),
                IsClose = false
            };
            btnMin.Click += (s, e) => this.WindowState = FormWindowState.Minimized;
            this.Controls.Add(btnMin);

            btnClose = new WindowControlButton
            {
                Text = "✕",
                Font = new Font("Segoe UI", 10.0f, FontStyle.Regular),
                Location = new Point(this.Width - 48, 0),
                Size = new Size(48, 32),
                IsClose = true
            };
            btnClose.Click += (s, e) => Application.Exit();
            this.Controls.Add(btnClose);

            // Centered Static Icon (224x140)
            animControl = new LatchAnimationControl
            {
                Size = new Size(224, 140),
                Location = new Point((this.Width - 224) / 2, 50),
                IsActiveWorking = true
            };
            animControl.MouseDown += Window_MouseDown;
            this.Controls.Add(animControl);

            // Center-aligned Status Label
            lblStatus = new Label
            {
                Text = "Checking for updates",
                ForeColor = Theme.SubText,
                Font = new Font("Segoe UI", 13.0f, FontStyle.Regular),
                Location = new Point(30, 230),
                Size = new Size(this.Width - 60, 36),
                TextAlign = ContentAlignment.MiddleCenter,
                BackColor = Theme.Bg
            };
            lblStatus.MouseDown += Window_MouseDown;
            this.Controls.Add(lblStatus);

            // Centered Progress Bar (smooth animated pill, 16px thickness)
            progressBar = new CustomProgressBar
            {
                Location = new Point((this.Width - 590) / 2, 310),
                Size = new Size(590, 16),
                Value = 0,
                BarColor = Theme.Accent,
                Visible = false
            };
            progressBar.ValueChanged += (percent) =>
            {
                InvokeUi(() =>
                {
                    lblProgressPercent.Text = percent + "%";
                });
            };
            this.Controls.Add(progressBar);

            // Progress percentage at the end of the progress bar
            lblProgressPercent = new Label
            {
                Location = new Point((this.Width - 590) / 2 + 590, 305),
                Size = new Size(60, 26),
                Text = "0%",
                TextAlign = ContentAlignment.MiddleLeft,
                ForeColor = Theme.SubText,
                Font = new Font("Segoe UI", 11.0f, FontStyle.Bold),
                BackColor = Theme.Bg,
                Visible = false
            };
            this.Controls.Add(lblProgressPercent);

            // Initialize progress layout without percent space
            UpdateProgressLayout(showPercent: false);

            // Failure Log Trace Box (hidden until error)
            txtLogTrace = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                BackColor = Theme.LogBg,
                ForeColor = Theme.DangerFg,
                Font = new Font("Consolas", 9.5f),
                BorderStyle = BorderStyle.None,
                Location = new Point((this.Width - 650) / 2, 290),
                Size = new Size(650, 240),
                Visible = false
            };
            this.Controls.Add(txtLogTrace);

            // Centered Button Panel
            buttonPanel = new Panel
            {
                Location = new Point((this.Width - 510) / 2, 390),
                Size = new Size(510, 46),
                BackColor = Theme.Bg,
                Visible = false
            };

            btnPrimary = new ModernButton
            {
                Text = "Launch",
                Size = new Size(165, 46),
                BackColor = Theme.Accent,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 11.0f, FontStyle.Bold),
                CornerRadius = 10
            };
            btnPrimary.Click += BtnPrimary_Click;

            btnSecondary = new ModernButton
            {
                Text = "Reinstall",
                Size = new Size(165, 46),
                BackColor = Theme.Surface,
                ForeColor = Theme.Text,
                Font = new Font("Segoe UI", 10.5f, FontStyle.Regular),
                CornerRadius = 10
            };
            btnSecondary.Click += (s, e) =>
            {
                if (btnSecondary.Text == "Close")
                {
                    Application.Exit();
                }
                else if (btnSecondary.Text == "Retry")
                {
                    RetryPipeline();
                }
                else
                {
                    if (release == null)
                    {
                        RetryPipeline();
                    }
                    else
                    {
                        StartInstallFlow(forceReinstall: true);
                    }
                }
            };

            btnDanger = new ModernButton
            {
                Text = "Uninstall",
                Size = new Size(150, 46),
                BackColor = Theme.DangerBg,
                ForeColor = Theme.DangerFg,
                Font = new Font("Segoe UI", 10.5f, FontStyle.Regular),
                CornerRadius = 10
            };
            btnDanger.Click += BtnDanger_Click;

            buttonPanel.Controls.Add(btnDanger);
            buttonPanel.Controls.Add(btnSecondary);
            buttonPanel.Controls.Add(btnPrimary);
            this.Controls.Add(buttonPanel);

            this.MouseDown += Window_MouseDown;
            this.Paint += SetupForm_Paint;
            this.Shown += SetupForm_Shown;
            this.Load += (s, e) => UpdateRoundedRegion();
        }

        private void UpdateProgressLayout(bool showPercent)
        {
            InvokeUi(() =>
            {
                lblProgressPercent.Visible = showPercent;
                int totalWidth = 590;
                int barY = 310;
                int barH = 16;

                if (showPercent)
                {
                    int percentW = 60;
                    int gap = 12;
                    int barW = totalWidth - percentW - gap;
                    int startX = (this.Width - totalWidth) / 2;

                    progressBar.Location = new Point(startX, barY);
                    progressBar.Size = new Size(barW, barH);

                    lblProgressPercent.Location = new Point(startX + barW + gap, barY - 5);
                    lblProgressPercent.Size = new Size(percentW, 26);
                }
                else
                {
                    int startX = (this.Width - totalWidth) / 2;
                    progressBar.Location = new Point(startX, barY);
                    progressBar.Size = new Size(totalWidth, barH);
                }
            });
        }

        private void UpdateRoundedRegion()
        {
            try
            {
                IntPtr hRgn = CreateRoundRectRgn(0, 0, this.Width, this.Height, 26, 26);
                if (hRgn != IntPtr.Zero)
                {
                    Region oldRegion = this.Region;
                    this.Region = Region.FromHrgn(hRgn);
                    DeleteObject(hRgn);
                    if (oldRegion != null)
                    {
                        oldRegion.Dispose();
                    }
                }
            }
            catch { }
        }

        private void SetupForm_Paint(object sender, PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath path = GetRoundedRectPath(new Rectangle(0, 0, this.Width - 1, this.Height - 1), 26))
            using (Pen pen = new Pen(Theme.Border, 1))
            {
                e.Graphics.DrawPath(pen, path);
            }
        }

        private void Window_MouseDown(object sender, MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left)
            {
                ReleaseCapture();
                SendMessage(this.Handle, WM_NCLBUTTONDOWN, HTCAPTION, 0);
            }
        }

        private void SetupForm_Shown(object sender, EventArgs e)
        {
            Logger.Info("SetupForm displayed. Starting background pipeline thread.");
            Thread worker = new Thread(RunSetupPipeline)
            {
                IsBackground = true
            };
            worker.Start();
        }

        private void RunSetupPipeline()
        {
            try
            {
                Logger.Info("Detecting currently installed product...");
                SetStatus("Detecting installed version");
                installed = InstalledProduct.Detect();
                Logger.Info(string.Format("Detection result: IsInstalled={0}, Version={1}, Path={2}, ProductCodes=[{3}]",
                    installed.IsInstalled,
                    installed.Version ?? "none",
                    installed.ExePath ?? "none",
                    string.Join(", ", installed.ProductCodes != null ? installed.ProductCodes.ToArray() : new string[0])
                ));

                bool doUninstall = HasArg("--uninstall", "-u");
                if (doUninstall)
                {
                    Logger.Info("Uninstall requested via arguments.");
                    PerformUninstall();
                    return;
                }

                if (HasArg("--offline", "-off"))
                {
                    Logger.Info("Offline simulation mode active via argument.");
                    if (installed.IsInstalled)
                    {
                        ShowOfflineInstalledState();
                    }
                    else
                    {
                        ShowOfflineUninstalledState();
                    }
                    return;
                }

                Logger.Info("Fetching latest release information...");
                SetStatus("Initialising");
                try
                {
                    release = ReleaseFetcher.FetchLatestRelease();
                    Logger.Info(string.Format("Latest release resolved: Version={0}, Url={1}, Asset={2}, Size={3}",
                        release.Version,
                        release.DownloadUrl,
                        release.AssetName,
                        release.Size
                    ));
                }
                catch (Exception netEx)
                {
                    Logger.Warn("Failed to fetch release metadata: " + netEx.Message);
                    if (ReleaseFetcher.IsNetworkOrDnsError(netEx))
                    {
                        if (installed.IsInstalled)
                        {
                            ShowOfflineInstalledState();
                            return;
                        }
                        else
                        {
                            ShowOfflineUninstalledState();
                            return;
                        }
                    }
                    throw;
                }

                int cmp = ReleaseFetcher.CompareVersions(release.Version, installed.Version);

                if (!installed.IsInstalled)
                {
                    Logger.Info("Product not installed. Automatically installing latest release...");
                    StartInstallFlow(forceReinstall: false);
                }
                else
                {
                    Logger.Info("Product is installed. Showing 3 action buttons (Launch, Reinstall/Update, Uninstall).");
                    ShowInstalledState(cmp > 0);
                }
            }
            catch (Exception ex)
            {
                Logger.Error("Exception in RunSetupPipeline", ex);
                if (ReleaseFetcher.IsNetworkOrDnsError(ex))
                {
                    if (installed != null && installed.IsInstalled)
                    {
                        ShowOfflineInstalledState();
                    }
                    else
                    {
                        ShowOfflineUninstalledState();
                    }
                }
                else
                {
                    ShowErrorState("Setup error: " + (ex.Message != null ? ex.Message.TrimEnd('.') : "Unknown error"));
                }
            }
        }

        private void StartInstallFlow(bool forceReinstall)
        {
            Thread worker = new Thread(() =>
            {
                try
                {
                    InvokeUi(() =>
                    {
                        if (animControl != null) animControl.IsActiveWorking = true;
                        txtLogTrace.Visible = false;
                        this.Height = NormalHeight;
                        UpdateRoundedRegion();
                        buttonPanel.Visible = false;
                        progressBar.Visible = true;
                        progressBar.Value = 0;
                        lblProgressPercent.Text = "0%";
                        UpdateProgressLayout(showPercent: true);
                    });

                    string actionText = (installed != null && installed.IsInstalled)
                        ? (forceReinstall ? "Reinstalling" : "Updating to")
                        : "Downloading";

                    SetStatus(string.Format("{0} v{1}", actionText, release != null ? release.Version : "latest"));

                    string tempMsi = Path.Combine(Path.GetTempPath(), "Latch-updates", "LatchSetup.msi");
                    string computedHash;

                    ReleaseFetcher.DownloadFile(
                        release.DownloadUrl,
                        tempMsi,
                        release.Size,
                        (percent, current, total) =>
                        {
                            InvokeUi(() =>
                            {
                                progressBar.Value = percent;
                                lblProgressPercent.Text = percent + "%";
                                if (!lblProgressPercent.Visible) UpdateProgressLayout(showPercent: true);
                                SetStatus(string.Format("Downloading v{0}", release != null ? release.Version : "latest"));
                            });
                        },
                        out computedHash
                    );

                    // Check hash integrity if expected digest was provided
                    if (!string.IsNullOrEmpty(release.ExpectedSha256) &&
                        !string.Equals(release.ExpectedSha256, computedHash, StringComparison.OrdinalIgnoreCase))
                    {
                        InvokeUi(() => UpdateProgressLayout(showPercent: false));
                        ShowErrorState("Security error: SHA-256 verification failed");
                        return;
                    }

                    SetStatus("Installing");
                    InvokeUi(() =>
                    {
                        UpdateProgressLayout(showPercent: false);
                        progressBar.Value = 100;
                    });

                    int exitCode;
                    string logTrace;
                    bool ok = InstallerRunner.InstallMsi(tempMsi, forceReinstall, out exitCode, out logTrace);

                    if (ok)
                    {
                        SetStatus("Installation complete");
                        installed = InstalledProduct.Detect();
                        InvokeUi(() =>
                        {
                            if (animControl != null) animControl.IsActiveWorking = false;
                            progressBar.Visible = false;
                            UpdateProgressLayout(showPercent: false);
                            txtLogTrace.Visible = false;

                            btnPrimary.Text = "Launch";
                            btnPrimary.Size = new Size(170, 46);
                            btnPrimary.Location = new Point(0, 0);
                            btnPrimary.Visible = true;

                            btnSecondary.Visible = false;
                            btnDanger.Visible = false;

                            buttonPanel.Size = new Size(170, 46);
                            buttonPanel.Location = new Point((this.Width - 170) / 2, 390);
                            buttonPanel.Visible = true;
                            buttonPanel.BringToFront();
                        });
                    }
                    else
                    {
                        InvokeUi(() => UpdateProgressLayout(showPercent: false));
                        ShowErrorState(string.Format("Installation failed (Code: {0})", exitCode), logTrace);
                    }
                }
                catch (Exception ex)
                {
                    Logger.Error("Download or installation failed", ex);
                    InvokeUi(() => lblProgressPercent.Visible = false);
                    if (ReleaseFetcher.IsNetworkOrDnsError(ex))
                    {
                        if (installed != null && installed.IsInstalled)
                        {
                            ShowOfflineInstalledState();
                        }
                        else
                        {
                            ShowOfflineUninstalledState();
                        }
                    }
                    else
                    {
                        ShowErrorState("Download or installation failed: " + (ex.Message != null ? ex.Message.TrimEnd('.') : "Unknown error"));
                    }
                }
            })
            {
                IsBackground = true
            };
            worker.Start();
        }

        private void PerformUninstall()
        {
            try
            {
                Logger.Info("PerformUninstall initiated.");
                SetStatus("Uninstalling");
                InvokeUi(() =>
                {
                    if (animControl != null) animControl.IsActiveWorking = true;
                    progressBar.Visible = true;
                    progressBar.Value = 40;
                    UpdateProgressLayout(showPercent: false);
                    buttonPanel.Visible = false;
                });

                int exitCode;
                string logTrace;
                bool ok = InstallerRunner.Uninstall(out exitCode, out logTrace);
                Logger.Info(string.Format("Uninstall completed: ok={0}, exitCode={1}", ok, exitCode));

                if (ok || exitCode == 0 || exitCode == 1605)
                {
                    InstallerRunner.RemoveCredentialStore();
                }

                installed = InstalledProduct.Detect();

                InvokeUi(() =>
                {
                    if (animControl != null) animControl.IsActiveWorking = false;
                    progressBar.Visible = false;
                    UpdateProgressLayout(showPercent: false);

                    if (ok)
                    {
                        txtLogTrace.Visible = false;
                        this.Height = NormalHeight;
                        UpdateRoundedRegion();
                        SetStatus("Uninstalled successfully");

                        btnPrimary.Text = "Close";
                        btnPrimary.Size = new Size(170, 46);
                        btnPrimary.Location = new Point(0, 0);
                        btnPrimary.Visible = true;

                        btnSecondary.Visible = false;
                        btnDanger.Visible = false;

                        buttonPanel.Size = new Size(170, 46);
                        buttonPanel.Location = new Point((this.Width - 170) / 2, 390);
                        buttonPanel.Visible = true;
                        buttonPanel.BringToFront();
                    }
                    else
                    {
                        ShowErrorState(string.Format("Uninstall failed (Code: {0})", exitCode), logTrace);
                    }
                });
            }
            catch (Exception ex)
            {
                Logger.Error("Exception in PerformUninstall", ex);
                ShowErrorState("Uninstall failed: " + (ex.Message != null ? ex.Message.TrimEnd('.') : "Unknown error"));
            }
        }

        private void ShowInstalledState(bool updateAvailable)
        {
            InvokeUi(() =>
            {
                if (animControl != null) animControl.IsActiveWorking = false;
                progressBar.Visible = false;
                UpdateProgressLayout(showPercent: false);
                txtLogTrace.Visible = false;
                this.Height = NormalHeight;
                UpdateRoundedRegion();

                if (updateAvailable)
                {
                    SetStatus(string.Format("v{0} is installed, update v{1} available", installed.Version, release != null ? release.Version : "new"));
                    btnSecondary.Text = "Update";
                }
                else
                {
                    SetStatus(string.Format("v{0} is installed and up to date", installed.Version));
                    btnSecondary.Text = "Reinstall";
                }

                btnDanger.Text = "Uninstall";
                btnDanger.Size = new Size(150, 46);
                btnDanger.Location = new Point(0, 0);
                btnDanger.Visible = true;

                btnSecondary.Size = new Size(165, 46);
                btnSecondary.Location = new Point(165, 0);
                btnSecondary.Visible = true;

                btnPrimary.Text = "Launch";
                btnPrimary.Size = new Size(165, 46);
                btnPrimary.Location = new Point(345, 0);
                btnPrimary.Visible = true;

                buttonPanel.Size = new Size(510, 46);
                buttonPanel.Location = new Point((this.Width - 510) / 2, 390);
                buttonPanel.Visible = true;
                buttonPanel.BringToFront();
            });
        }

        private void ShowOfflineInstalledState()
        {
            InvokeUi(() =>
            {
                if (animControl != null) animControl.IsActiveWorking = false;
                progressBar.Visible = false;
                UpdateProgressLayout(showPercent: false);
                txtLogTrace.Visible = false;
                this.Height = NormalHeight;
                UpdateRoundedRegion();

                string verText = (installed != null && !string.IsNullOrEmpty(installed.Version))
                    ? ("v" + installed.Version + " is installed")
                    : "app is installed";
                SetStatus(string.Format("No internet connection, {0}", verText));

                btnDanger.Text = "Uninstall";
                btnDanger.Size = new Size(150, 46);
                btnDanger.Location = new Point(0, 0);
                btnDanger.Visible = true;

                btnSecondary.Text = "Retry";
                btnSecondary.Size = new Size(165, 46);
                btnSecondary.Location = new Point(165, 0);
                btnSecondary.Visible = true;

                btnPrimary.Text = "Launch";
                btnPrimary.Size = new Size(165, 46);
                btnPrimary.Location = new Point(345, 0);
                btnPrimary.Visible = true;

                buttonPanel.Size = new Size(510, 46);
                buttonPanel.Location = new Point((this.Width - 510) / 2, 390);
                buttonPanel.Visible = true;
                buttonPanel.BringToFront();
            });
        }

        private void ShowOfflineUninstalledState()
        {
            InvokeUi(() =>
            {
                if (animControl != null) animControl.IsActiveWorking = false;
                progressBar.Visible = false;
                UpdateProgressLayout(showPercent: false);
                txtLogTrace.Visible = false;
                this.Height = NormalHeight;
                UpdateRoundedRegion();

                SetStatus("No internet connection, please check your network and try again");

                btnSecondary.Text = "Close";
                btnSecondary.Size = new Size(155, 46);
                btnSecondary.Location = new Point(0, 0);
                btnSecondary.Visible = true;

                btnPrimary.Text = "Retry";
                btnPrimary.Size = new Size(155, 46);
                btnPrimary.Location = new Point(175, 0);
                btnPrimary.Visible = true;

                btnDanger.Visible = false;

                buttonPanel.Size = new Size(330, 46);
                buttonPanel.Location = new Point((this.Width - 330) / 2, 390);
                buttonPanel.Visible = true;
                buttonPanel.BringToFront();
            });
        }

        private void ShowErrorState(string msg, string logTrace = null)
        {
            InvokeUi(() =>
            {
                if (animControl != null) animControl.IsActiveWorking = false;
                progressBar.Visible = false;
                UpdateProgressLayout(showPercent: false);
                SetStatus(msg != null ? msg.TrimEnd('.') : "");

                if (!string.IsNullOrEmpty(logTrace))
                {
                    txtLogTrace.Text = logTrace;
                    txtLogTrace.Visible = true;
                    this.Height = ExpandedHeight;
                    UpdateRoundedRegion();
                    buttonPanel.Location = new Point((this.Width - 330) / 2, 590);
                }
                else
                {
                    txtLogTrace.Visible = false;
                    this.Height = NormalHeight;
                    UpdateRoundedRegion();
                    buttonPanel.Location = new Point((this.Width - 330) / 2, 390);
                }

                btnSecondary.Text = "Close";
                btnSecondary.Size = new Size(155, 46);
                btnSecondary.Location = new Point(0, 0);
                btnSecondary.Visible = true;

                btnPrimary.Text = "Retry";
                btnPrimary.Size = new Size(155, 46);
                btnPrimary.Location = new Point(175, 0);
                btnPrimary.Visible = true;

                btnDanger.Visible = false;

                buttonPanel.Size = new Size(330, 46);
                buttonPanel.Visible = true;
                buttonPanel.BringToFront();
            });
        }

        private void RetryPipeline()
        {
            InvokeUi(() =>
            {
                txtLogTrace.Visible = false;
                this.Height = NormalHeight;
                UpdateRoundedRegion();
                buttonPanel.Visible = false;
                progressBar.Visible = false;
                progressBar.Value = 0;
                UpdateProgressLayout(showPercent: false);
                if (animControl != null) animControl.IsActiveWorking = true;
                SetStatus("Checking for updates");
            });

            Thread worker = new Thread(RunSetupPipeline)
            {
                IsBackground = true
            };
            worker.Start();
        }

        private void BtnPrimary_Click(object sender, EventArgs e)
        {
            if (btnPrimary.Text == "Launch")
            {
                InstalledProduct current = InstalledProduct.Detect();
                string exe = current != null && current.IsInstalled ? current.ExePath : (installed != null ? installed.ExePath : null);
                InstallerRunner.LaunchApp(exe);
                Application.Exit();
            }
            else if (btnPrimary.Text == "Close")
            {
                Application.Exit();
            }
            else if (btnPrimary.Text == "Retry")
            {
                RetryPipeline();
            }
        }

        private void BtnDanger_Click(object sender, EventArgs e)
        {
            DialogResult dr = MessageBox.Show(
                "Are you sure you want to uninstall?",
                "Uninstall",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question
            );

            if (dr == DialogResult.Yes)
            {
                InvokeUi(() =>
                {
                    buttonPanel.Visible = false;
                    progressBar.Visible = true;
                    progressBar.Value = 40;
                    UpdateProgressLayout(showPercent: false);
                });
                Thread worker = new Thread(PerformUninstall) { IsBackground = true };
                worker.Start();
            }
        }

        private void SetStatus(string text)
        {
            InvokeUi(() => lblStatus.Text = text);
        }

        private void InvokeUi(Action action)
        {
            if (this.IsDisposed) return;
            if (this.InvokeRequired) this.BeginInvoke(action);
            else action();
        }

        private bool HasArg(string arg1, string arg2)
        {
            foreach (string a in args)
            {
                if (string.Equals(a, arg1, StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(a, arg2, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

        public static GraphicsPath GetRoundedRectPath(Rectangle rect, int radius)
        {
            GraphicsPath path = new GraphicsPath();
            if (rect.Width <= 0 || rect.Height <= 0) return path;

            int d = radius * 2;
            if (d > rect.Width) d = rect.Width;
            if (d > rect.Height) d = rect.Height;

            Rectangle arc = new Rectangle(rect.X, rect.Y, d, d);

            path.AddArc(arc, 180, 90);

            arc.X = rect.Right - d;
            path.AddArc(arc, 270, 90);

            arc.Y = rect.Bottom - d;
            path.AddArc(arc, 0, 90);

            arc.X = rect.Left;
            path.AddArc(arc, 90, 90);

            path.CloseFigure();
            return path;
        }
    }

}
