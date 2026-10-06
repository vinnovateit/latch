using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace LatchSetup
{
    // Fully rounded (pill) progress bar with buttery-smooth animated interpolation,
    // sleek gradient fill, and a sweeping gloss shimmer reflection.
    public class CustomProgressBar : Control
    {
        private float targetVal = 0f;
        private float currentVal = 0f;
        private float shimmerOffset = 0f;
        private readonly Timer animTimer;

        public Color BarColor { get; set; }
        public Color TrackColor { get; set; }

        public event Action<int> ValueChanged;

        public int Value
        {
            get { return (int)Math.Round(targetVal); }
            set
            {
                targetVal = Math.Max(0f, Math.Min(100f, (float)value));
                if (!animTimer.Enabled) animTimer.Start();
            }
        }

        public CustomProgressBar()
        {
            SetStyle(
                ControlStyles.UserPaint |
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer,
                true
            );
            BarColor = Theme.Accent;
            TrackColor = Theme.Surface;
            BackColor = Theme.Bg;

            animTimer = new Timer { Interval = 16 }; // ~60 FPS
            animTimer.Tick += AnimTimer_Tick;
        }

        private void AnimTimer_Tick(object sender, EventArgs e)
        {
            bool needsRedraw = false;

            // Smooth spring lerp toward target
            float diff = targetVal - currentVal;
            if (Math.Abs(diff) > 0.05f)
            {
                currentVal += diff * 0.15f;
                needsRedraw = true;
            }
            else if (currentVal != targetVal)
            {
                currentVal = targetVal;
                needsRedraw = true;
            }

            // Continuous gloss shimmer sweep across the filled bar
            if (currentVal > 0f)
            {
                shimmerOffset += 0.025f;
                if (shimmerOffset > 1.5f) shimmerOffset = -0.5f;
                needsRedraw = true;
            }

            if (needsRedraw)
            {
                if (ValueChanged != null)
                {
                    ValueChanged((int)Math.Round(currentVal));
                }
                Invalidate();
            }
            else if (currentVal >= 100f || currentVal <= 0f)
            {
                if (Math.Abs(targetVal - currentVal) < 0.01f && currentVal <= 0f)
                {
                    animTimer.Stop();
                }
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing && animTimer != null)
            {
                animTimer.Stop();
                animTimer.Dispose();
            }
            base.Dispose(disposing);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.Clear(Theme.Bg);
            g.SmoothingMode = SmoothingMode.AntiAlias;

            int radius = Height / 2;
            using (GraphicsPath track = SetupForm.GetRoundedRectPath(new Rectangle(0, 0, Width - 1, Height - 1), radius))
            using (SolidBrush trackBrush = new SolidBrush(TrackColor))
            {
                g.FillPath(trackBrush, track);
            }

            if (currentVal > 0f)
            {
                int fillWidth = Math.Max(Height, (int)((Width * currentVal) / 100.0f));
                Rectangle fillRect = new Rectangle(0, 0, fillWidth - 1, Height - 1);
                using (GraphicsPath fill = SetupForm.GetRoundedRectPath(fillRect, radius))
                {
                    // Gradient base: deep accent to vibrant accent
                    Color startColor = BarColor;
                    Color endColor = Color.FromArgb(
                        Math.Min(255, startColor.R + 35),
                        Math.Min(255, startColor.G + 15),
                        Math.Min(255, startColor.B + 20)
                    );

                    using (LinearGradientBrush barBrush = new LinearGradientBrush(
                        new Point(0, 0),
                        new Point(fillWidth, 0),
                        startColor,
                        endColor))
                    {
                        g.FillPath(barBrush, fill);
                    }

                    // Sweeping gloss shimmer wave
                    float shimmerX = fillWidth * shimmerOffset;
                    int shimmerWidth = Math.Max(35, fillWidth / 3);
                    Rectangle shimmerRect = new Rectangle((int)shimmerX, 0, shimmerWidth, Height);

                    if (shimmerRect.Right > 0 && shimmerRect.Left < fillWidth)
                    {
                        using (LinearGradientBrush shimmerBrush = new LinearGradientBrush(
                            shimmerRect,
                            Color.FromArgb(0, 255, 255, 255),
                            Color.FromArgb(60, 255, 255, 255),
                            LinearGradientMode.Horizontal))
                        {
                            ColorBlend blend = new ColorBlend();
                            blend.Colors = new Color[] {
                                Color.FromArgb(0, 255, 255, 255),
                                Color.FromArgb(70, 255, 255, 255),
                                Color.FromArgb(0, 255, 255, 255)
                            };
                            blend.Positions = new float[] { 0f, 0.5f, 1f };
                            shimmerBrush.InterpolationColors = blend;

                            GraphicsState state = g.Save();
                            g.SetClip(fill);
                            g.FillRectangle(shimmerBrush, shimmerRect);
                            g.Restore(state);
                        }
                    }
                }
            }
        }
    }

    // Rounded rectangle button. Clears to the window color so corners stay clean.
    public class ModernButton : Button
    {
        private bool isHovered = false;
        private bool isPressed = false;
        public int CornerRadius { get; set; }

        public ModernButton()
        {
            CornerRadius = 8;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            BackColor = Theme.Surface;
            ForeColor = Theme.Text;
            Cursor = Cursors.Hand;
        }

        protected override void OnMouseEnter(EventArgs e)
        {
            base.OnMouseEnter(e);
            isHovered = true;
            Invalidate();
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            base.OnMouseLeave(e);
            isHovered = false;
            isPressed = false;
            Invalidate();
        }

        protected override void OnMouseDown(MouseEventArgs mevent)
        {
            base.OnMouseDown(mevent);
            isPressed = true;
            Invalidate();
        }

        protected override void OnMouseUp(MouseEventArgs mevent)
        {
            base.OnMouseUp(mevent);
            isPressed = false;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs pevent)
        {
            Graphics g = pevent.Graphics;
            g.Clear(Theme.Bg);
            g.SmoothingMode = SmoothingMode.AntiAlias;

            Color drawColor = BackColor;
            if (isPressed) drawColor = Theme.Press(BackColor);
            else if (isHovered) drawColor = Theme.Hover(BackColor);

            using (GraphicsPath path = SetupForm.GetRoundedRectPath(new Rectangle(0, 0, Width - 1, Height - 1), CornerRadius))
            using (SolidBrush brush = new SolidBrush(drawColor))
            {
                g.FillPath(brush, path);
            }

            TextRenderer.DrawText(g, Text, Font, new Rectangle(0, 0, Width, Height), ForeColor,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
    }

    // Minimize / close button. Fills its whole rectangle on hover, so placed flush
    // to the top-right edge it reads as part of the window frame, not a container.
    public class WindowControlButton : Button
    {
        private bool isHovered = false;
        private bool isPressed = false;
        public bool IsClose { get; set; }

        public WindowControlButton()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            BackColor = Theme.Bg;
            Size = new Size(48, 32);
            Cursor = Cursors.Hand;
        }

        protected override void OnMouseEnter(EventArgs e)
        {
            base.OnMouseEnter(e);
            isHovered = true;
            Invalidate();
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            base.OnMouseLeave(e);
            isHovered = false;
            isPressed = false;
            Invalidate();
        }

        protected override void OnMouseDown(MouseEventArgs mevent)
        {
            base.OnMouseDown(mevent);
            isPressed = true;
            Invalidate();
        }

        protected override void OnMouseUp(MouseEventArgs mevent)
        {
            base.OnMouseUp(mevent);
            isPressed = false;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs pevent)
        {
            Graphics g = pevent.Graphics;
            Color bg = Theme.Bg;
            if (isHovered || isPressed)
            {
                if (IsClose) bg = isPressed ? Color.FromArgb(185, 28, 28) : Color.FromArgb(220, 38, 38);
                else bg = isPressed ? Theme.Press(Theme.Surface) : Theme.Surface;
            }
            g.Clear(bg);

            Color textColor = (IsClose && (isHovered || isPressed)) ? Color.White : Theme.MutedText;
            TextRenderer.DrawText(g, Text, Font, new Rectangle(0, 0, Width, Height), textColor,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
    }
}
