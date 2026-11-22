// Stitches screenshots of a scrolled page into one tall image (used by tools/screenshots.ps1).
//
// Every screenshot shows fixed parts (status bar, top bar, tabs above; navigation bar below)
// and the scrolled page in between; floating buttons sit over the lower part of the page.
// The fixed parts are found by comparing two screenshots row by row, the scroll distance by
// finding where the rows of the next screenshot appear in the previous one.
//
// Each piece of the page is taken from the upper part of the screenshot it appears in, far
// from the floating buttons; only the last screenshot is used down to its bottom, so the
// buttons appear once, where they are on the real screen.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

public static class Stitcher
{
    private sealed class Img
    {
        public int W, H, Stride;
        public byte[] Px;
        public ulong[] Full;  // hash of each whole row
        public ulong[] Left;  // hash of the left 4..75 % of each row (floating buttons sit to the right)
        public bool[] Info;   // whether the left part of the row shows anything (not one color)
    }

    private static Img Load(string path)
    {
        using (var bmp = new Bitmap(path))
        {
            var data = bmp.LockBits(new Rectangle(0, 0, bmp.Width, bmp.Height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            var img = new Img { W = bmp.Width, H = bmp.Height, Stride = data.Stride, Px = new byte[data.Stride * bmp.Height] };
            Marshal.Copy(data.Scan0, img.Px, 0, img.Px.Length);
            bmp.UnlockBits(data);
            int x0 = img.W * 4 / 100, x1 = img.W * 75 / 100;
            img.Full = new ulong[img.H];
            img.Left = new ulong[img.H];
            img.Info = new bool[img.H];
            for (int y = 0; y < img.H; y++)
            {
                img.Full[y] = Hash(img, y, 0, img.W);
                img.Left[y] = Hash(img, y, x0, x1);
                img.Info[y] = !Uniform(img, y, x0, x1);
            }
            return img;
        }
    }

    private static ulong Hash(Img im, int y, int x0, int x1)
    {
        ulong h = 1469598103934665603UL;
        int start = y * im.Stride + x0 * 4, end = y * im.Stride + x1 * 4;
        for (int i = start; i < end; i++) { h ^= im.Px[i]; h *= 1099511628211UL; }
        return h;
    }

    private static bool Uniform(Img im, int y, int x0, int x1)
    {
        int row = y * im.Stride, a = row + x0 * 4;
        for (int x = x0 + 1; x < x1; x++)
        {
            int b = row + x * 4;
            if (im.Px[a] != im.Px[b] || im.Px[a + 1] != im.Px[b + 1] || im.Px[a + 2] != im.Px[b + 2]) return false;
        }
        return true;
    }

    /// Rows at the top and at the bottom that are the same in both screenshots (they never scroll).
    private static void Fixed(Img a, Img b, out int header, out int footer)
    {
        int H = a.H;
        header = 0;
        while (header < H * 2 / 5 && a.Full[header] == b.Full[header]) header++;
        footer = 0;
        while (footer < H * 2 / 5 && a.Full[H - 1 - footer] == b.Full[H - 1 - footer]) footer++;
    }

    /// How far the page moved from a to b (rows): 0 = not at all, -1 = no reliable match.
    /// Among equally good matches the one closest to [expected] wins (repeating content).
    private static int Shift(Img a, Img b, int top, int bottom, int expected)
    {
        int n = bottom - top;
        bool moved = false;
        for (int y = top; y < bottom; y++) if (a.Left[y] != b.Left[y]) { moved = true; break; }
        if (!moved) return 0;
        var scores = new double[n];
        double best = 0;
        for (int d = 1; d < n - 20; d++)
        {
            int match = 0, total = 0;
            for (int y = top; y < bottom - d; y++)
            {
                if (!b.Info[y]) continue;
                total++;
                if (a.Left[y + d] == b.Left[y]) match++;
            }
            if (total < 15) continue;
            scores[d] = (double)match / total;
            if (scores[d] > best) best = scores[d];
        }
        if (best < 0.9) return -1;
        int chosen = -1;
        for (int d = 1; d < n; d++)
            if (scores[d] >= best - 0.01 && (chosen < 0 || Math.Abs(d - expected) < Math.Abs(chosen - expected))) chosen = d;
        return chosen;
    }

    /// The scroll distance between two screenshots: 0 = the page did not move (its end is reached),
    /// -1 = the screenshots do not fit together.
    public static int Offset(string previous, string next, int expected)
    {
        Img a = Load(previous), b = Load(next);
        if (a.W != b.W || a.H != b.H) return -1;
        int header, footer;
        Fixed(a, b, out header, out footer);
        if (a.H - footer - header < 40) return 0;
        return Shift(a, b, header, a.H - footer, expected);
    }

    /// Writes [files] (consecutive screenshots, each scrolled further) as one image; returns how many were used.
    public static int Stitch(string[] files, int expected, string output)
    {
        var imgs = new List<Img>();
        foreach (var f in files) imgs.Add(Load(f));
        if (imgs.Count < 2)
        {
            File.Copy(files[0], output, true);
            return 1;
        }
        int header, footer;
        Fixed(imgs[0], imgs[1], out header, out footer);
        int W = imgs[0].W, H = imgs[0].H, stride = imgs[0].Stride, bottom = H - footer;
        var shifts = new List<int>();
        for (int i = 1; i < imgs.Count; i++)
        {
            int d = Shift(imgs[i - 1], imgs[i], header, bottom, expected);
            if (d <= 0) break;
            shifts.Add(d);
        }
        if (shifts.Count == 0)
        {
            File.Copy(files[0], output, true);
            return 1;
        }
        int total = H;
        foreach (int d in shifts) total += d;
        var px = new byte[stride * total];
        int at = 0;
        Action<Img, int, int> copy = (img, from, rows) =>
        {
            Buffer.BlockCopy(img.Px, from * stride, px, at * stride, rows * stride);
            at += rows;
        };
        // first screenshot: the fixed top and the page until the next screenshot takes over
        copy(imgs[0], 0, header + shifts[0]);
        // each further screenshot: only the band at its top that the following one does not cover
        for (int i = 1; i < shifts.Count; i++) copy(imgs[i], header, shifts[i]);
        // last screenshot: everything below its top bar, floating buttons and navigation bar included
        copy(imgs[shifts.Count], header, H - header);
        using (var bmp = new Bitmap(W, total, PixelFormat.Format32bppArgb))
        {
            var data = bmp.LockBits(new Rectangle(0, 0, W, total), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            Marshal.Copy(px, 0, data.Scan0, px.Length);
            bmp.UnlockBits(data);
            bmp.Save(output, ImageFormat.Png);
        }
        return shifts.Count + 1;
    }
}
