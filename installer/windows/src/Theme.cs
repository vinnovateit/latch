using System;
using System.Drawing;
using Microsoft.Win32;

namespace LatchSetup
{
    // Colors follow the Windows app light/dark setting, read once at startup.
    public static class Theme
    {
        public static readonly bool IsDark = DetectDark();

        public static readonly Color Accent = Color.FromArgb(192, 18, 33); // Latch icon red #C01221
        public static readonly Color AccentText = Color.White;

        public static readonly Color Bg = IsDark ? Color.FromArgb(24, 24, 27) : Color.FromArgb(250, 250, 250);
        public static readonly Color Surface = IsDark ? Color.FromArgb(39, 39, 42) : Color.FromArgb(228, 228, 231);
        public static readonly Color Border = IsDark ? Color.FromArgb(55, 55, 60) : Color.FromArgb(205, 205, 210);
        public static readonly Color Text = IsDark ? Color.White : Color.FromArgb(24, 24, 27);
        public static readonly Color SubText = IsDark ? Color.FromArgb(210, 210, 215) : Color.FromArgb(82, 82, 91);
        public static readonly Color MutedText = IsDark ? Color.FromArgb(160, 160, 170) : Color.FromArgb(113, 113, 122);
        public static readonly Color DangerBg = IsDark ? Color.FromArgb(45, 20, 20) : Color.FromArgb(254, 226, 226);
        public static readonly Color DangerFg = IsDark ? Color.FromArgb(248, 113, 113) : Color.FromArgb(185, 28, 28);
        public static readonly Color LogBg = IsDark ? Color.FromArgb(16, 16, 18) : Color.FromArgb(238, 238, 241);

        private static bool DetectDark()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                {
                    object v = key != null ? key.GetValue("AppsUseLightTheme") : null;
                    if (v is int) return (int)v == 0;
                }
            }
            catch (Exception ex)
            {
                Logger.Warn("Theme detection failed, defaulting to dark: " + ex.Message);
            }
            return true;
        }

        public static Color Hover(Color c)
        {
            int d = IsDark ? 25 : -18;
            if (c == Accent) d = -22;
            return Shift(c, d);
        }

        public static Color Press(Color c)
        {
            int d = IsDark ? -25 : -35;
            if (c == Accent) d = -40;
            return Shift(c, d);
        }

        private static Color Shift(Color c, int d)
        {
            return Color.FromArgb(Clamp(c.R + d), Clamp(c.G + d), Clamp(c.B + d));
        }

        private static int Clamp(int v)
        {
            return Math.Max(0, Math.Min(255, v));
        }
    }
}
