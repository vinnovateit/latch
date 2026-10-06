using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace LatchSetup
{
    // Static Latch icon control.
    // Displays the interlocking hands icon centered without animation or scaling.
    public class LatchAnimationControl : Control
    {
        private static readonly GraphicsPath[] LeftHandPaths;
        private static readonly GraphicsPath[] RightHandPaths;
        private static readonly Color DarkRed = Color.FromArgb(103, 0, 2); // #670002

        static LatchAnimationControl()
        {
            // Left Hand SVG Path definitions from app onboarding (viewport 50 x 36)
            LeftHandPaths = new GraphicsPath[]
            {
                ParseSvgPath("M17.96,23.886L22.941,28.654L17.839,33.361C16.024,34.959 13.707,34.99 11.64,34.339C10.925,34.113 10.3,33.674 9.758,33.155C6.525,30.057 5.381,28.706 2.368,25.228C2.154,24.981 1.956,24.719 1.788,24.439C-0.224,21.083 -0.416,18.358 0.605,13.134C0.711,12.591 0.889,12.061 1.156,11.575C2.619,8.908 4.817,6.413 9.354,1.823C9.783,1.39 10.269,1.007 10.819,0.744C13.281,-0.431 14.9,-0.158 17.596,1.169L28.95,12.113C29.214,12.366 29.43,12.668 29.573,13.005C31.041,16.474 30.533,18.28 29.349,21.152C25.189,17.183 18.659,11.282 18.659,11.282C17.979,10.873 14.194,9.674 12.19,11.859C10.186,14.045 8.413,19.978 12.706,23.369C14.34,24.65 15.522,24.68 17.96,23.886Z"),
                ParseSvgPath("M15.033,27.516C17.671,29.149 19.348,29.308 22.352,28.062L20.196,25.982C18.953,24.784 17.966,23.839 17.945,23.891C16.24,24.465 15.451,24.466 14.061,24.053C12.964,23.61 12.454,23.215 11.692,22.292L10.842,21.199L11.389,22.383C12.302,24.577 13.058,25.713 15.033,27.516Z"),
                ParseSvgPath("M15.519,10.63C13.119,10.925 12.128,11.445 10.934,13.85C11.427,12.316 11.847,11.464 12.908,9.962C14.912,7.205 16.061,6.311 18.162,6.166C20.883,5.84 22.377,6.348 24.995,8.261L29.247,12.391C30.828,15.317 30.982,17.509 29.346,21.164L20.774,13.211C18.782,11.296 17.615,10.669 15.519,10.63Z")
            };

            // Right Hand SVG Path definitions from app onboarding (viewport 50 x 36)
            RightHandPaths = new GraphicsPath[]
            {
                ParseSvgPath("M31.368,12.114L26.387,7.346L31.489,2.639C33.623,0.761 36.448,1.048 38.748,2.062C42.466,5.583 43.608,6.901 46.555,10.304L46.671,10.438C47.077,10.907 47.457,11.402 47.762,11.942C49.473,14.966 49.686,17.564 48.864,22.117C48.676,23.161 48.338,24.178 47.792,25.087C46.397,27.405 44.341,29.736 40.555,33.588C39.743,34.414 38.833,35.172 37.75,35.582C36.243,36.152 35.01,36.121 33.514,35.599C32.299,35.175 31.261,34.377 30.335,33.485L20.378,23.888C20.114,23.633 19.898,23.332 19.755,22.995C18.288,19.526 18.795,17.72 19.979,14.848C24.139,18.817 30.669,24.718 30.669,24.718C31.349,25.128 35.134,26.326 37.138,24.141C39.143,21.955 40.915,16.022 36.622,12.631C34.988,11.35 33.806,11.32 31.368,12.114Z"),
                ParseSvgPath("M26.994,7.938L31.355,12.136C33.06,11.563 33.925,11.571 35.315,11.984C36.413,12.428 36.874,12.786 37.636,13.708L38.486,14.802L37.94,13.617C37.026,11.424 36.27,10.287 34.295,8.485C31.657,6.852 29.998,6.692 26.994,7.938Z"),
                ParseSvgPath("M33.803,25.408C36.203,25.112 37.195,24.532 38.389,22.127C37.895,23.66 37.475,24.512 36.415,26.014C34.41,28.771 33.261,29.665 31.161,29.811C28.439,30.137 26.945,29.628 24.327,27.715L20.075,23.585C18.273,20.781 18.436,18.436 19.924,14.808L28.582,22.8C30.573,24.715 31.707,25.369 33.803,25.408Z")
            };
        }

        public bool IsActiveWorking { get; set; }

        public LatchAnimationControl()
        {
            SetStyle(
                ControlStyles.UserPaint |
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer,
                true
            );
            this.BackColor = Theme.Bg;
            this.Size = new Size(180, 115);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.Clear(Theme.Bg);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;

            float scale = Math.Min(Width / 70f, Height / 50f);
            float centerX = Width / 2f;
            float centerY = Height / 2f;

            // Draw Right Hand
            GraphicsState stateRight = g.Save();
            g.TranslateTransform(centerX, centerY);
            g.ScaleTransform(scale, scale);
            g.TranslateTransform(-25f, -18f); // Center of 50x36 viewport
            DrawHand(g, RightHandPaths);
            g.Restore(stateRight);

            // Draw Left Hand
            GraphicsState stateLeft = g.Save();
            g.TranslateTransform(centerX, centerY);
            g.ScaleTransform(scale, scale);
            g.TranslateTransform(-25f, -18f); // Center of 50x36 viewport
            DrawHand(g, LeftHandPaths);
            g.Restore(stateLeft);
        }

        private static void DrawHand(Graphics g, GraphicsPath[] paths)
        {
            using (SolidBrush mainBrush = new SolidBrush(Theme.Accent))
            using (SolidBrush darkBrush = new SolidBrush(DarkRed))
            {
                if (paths.Length > 0 && paths[0] != null) g.FillPath(mainBrush, paths[0]);
                if (paths.Length > 1 && paths[1] != null) g.FillPath(darkBrush, paths[1]);
                if (paths.Length > 2 && paths[2] != null) g.FillPath(darkBrush, paths[2]);
            }
        }

        private static GraphicsPath ParseSvgPath(string data)
        {
            GraphicsPath path = new GraphicsPath(FillMode.Winding);
            int i = 0;
            char command = '\0';
            float startX = 0, startY = 0, curX = 0, curY = 0;

            while (i < data.Length)
            {
                char c = data[i];
                if (char.IsWhiteSpace(c) || c == ',')
                {
                    i++;
                    continue;
                }
                if (char.IsLetter(c))
                {
                    command = c;
                    i++;
                    if (command == 'Z' || command == 'z')
                    {
                        path.CloseFigure();
                        curX = startX;
                        curY = startY;
                    }
                    continue;
                }

                switch (command)
                {
                    case 'M':
                        float[] m = ReadFloats(data, ref i, 2);
                        curX = m[0]; curY = m[1];
                        startX = curX; startY = curY;
                        path.StartFigure();
                        command = 'L';
                        break;
                    case 'L':
                        float[] l = ReadFloats(data, ref i, 2);
                        path.AddLine(curX, curY, l[0], l[1]);
                        curX = l[0]; curY = l[1];
                        break;
                    case 'C':
                        float[] cp = ReadFloats(data, ref i, 6);
                        path.AddBezier(curX, curY, cp[0], cp[1], cp[2], cp[3], cp[4], cp[5]);
                        curX = cp[4]; curY = cp[5];
                        break;
                    default:
                        i++;
                        break;
                }
            }
            return path;
        }

        private static float[] ReadFloats(string data, ref int i, int count)
        {
            float[] res = new float[count];
            for (int k = 0; k < count; k++)
            {
                while (i < data.Length && (char.IsWhiteSpace(data[i]) || data[i] == ',')) i++;
                int start = i;
                if (i < data.Length && (data[i] == '-' || data[i] == '+')) i++;
                while (i < data.Length && (char.IsDigit(data[i]) || data[i] == '.')) i++;
                if (i > start)
                {
                    string token = data.Substring(start, i - start);
                    float val;
                    float.TryParse(token, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out val);
                    res[k] = val;
                }
            }
            return res;
        }
    }
}
