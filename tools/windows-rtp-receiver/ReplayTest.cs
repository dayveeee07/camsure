using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Diagnostics;

// Host-only test sender. Synthetic H.264 from the native decoder probe; no phone proof.
internal static class ReplayTest
{
    public static int Run(string fixture, int port)
    {
        var units = new List<(byte[] Bytes, bool Key)>();
        using (var file = File.OpenRead(fixture))
        {
            while (file.Position < file.Length)
            {
                var header = new byte[48]; file.ReadExactly(header);
                var length = BinaryPrimitives.ReadInt32LittleEndian(header.AsSpan(8));
                var configLength = BinaryPrimitives.ReadInt32LittleEndian(header.AsSpan(12));
                if (length is < 1 or > 2 * 1024 * 1024 || configLength is < 0 or > 65536) throw new InvalidDataException();
                var bytes = new byte[configLength + length]; file.ReadExactly(bytes);
                units.Add((bytes, (BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(16)) & 1) != 0));
            }
        }
        using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        var destination = new IPEndPoint(IPAddress.Loopback, port);
        ushort sequence = 0;
        for (uint run = 1; run <= 2; run++)
        {
            var clock = Stopwatch.StartNew();
            for (var cycle = 0; cycle < 3; cycle++)
            for (var index = 0; index < units.Count; index++)
            {
                var unit = units[index]; var nals = Split(unit.Bytes);
                long pts = 1_000_000 + ((long)cycle * units.Count + index) * 33_333;
                uint timestamp = (uint)Math.Round((pts - 1_000_000) * .09, MidpointRounding.AwayFromZero);
                for (var n = 0; n < nals.Count; n++)
                {
                    var nal = nals[n];
                    if (nal.Length <= 1100)
                    {
                        var packet = ReceiverSelfTest.Packet(++sequence, timestamp, pts, nal, n == nals.Count - 1, unit.Key, run);
                        if (run == 1 && cycle == 1 && index == 10 && n == nals.Count - 1 && !unit.Key)
                        { Console.WriteLine("Synthetic induced RTP packet loss"); continue; }
                        socket.SendTo(packet, destination);
                    }
                    else
                    {
                        for (var offset = 1; offset < nal.Length;)
                        {
                            var count = Math.Min(1098, nal.Length - offset);
                            var payload = new byte[count + 2];
                            payload[0] = (byte)((nal[0] & 0xe0) | 28);
                            payload[1] = (byte)((nal[0] & 31) | (offset == 1 ? 128 : 0) | (offset + count == nal.Length ? 64 : 0));
                            nal.AsSpan(offset, count).CopyTo(payload.AsSpan(2));
                            var packet = ReceiverSelfTest.Packet(++sequence, timestamp, pts, payload, n == nals.Count - 1 && offset + count == nal.Length, unit.Key, run);
                            socket.SendTo(packet, destination); offset += count;
                        }
                    }
                }
                var deadline = ((long)cycle * units.Count + index + 1) / 30.0;
                while (clock.Elapsed.TotalSeconds < deadline) Thread.Sleep(1);
            }
            if (run == 1) { Console.WriteLine("Synthetic sender pause/restart (new stream identity)"); Thread.Sleep(1900); sequence = 0; }
        }
        Console.WriteLine("PASS: synthetic 1080p RTP replay sent with FU-A and stream stop/restart.");
        return 0;
    }
    private static List<byte[]> Split(byte[] bytes)
    {
        var result = new List<byte[]>(); var starts = new List<(int Offset, int Prefix)>();
        for (var i = 0; i + 3 < bytes.Length; i++)
        {
            if (bytes[i] != 0 || bytes[i + 1] != 0) continue;
            var prefix = bytes[i + 2] == 1 ? 3 : bytes[i + 2] == 0 && bytes[i + 3] == 1 ? 4 : 0;
            if (prefix == 0) continue;
            starts.Add((i, prefix)); i += prefix - 1;
        }
        for (var n = 0; n < starts.Count; n++)
        {
            var start = starts[n].Offset + starts[n].Prefix; var end = n + 1 < starts.Count ? starts[n + 1].Offset : bytes.Length;
            result.Add(bytes.AsSpan(start, end - start).ToArray());
        }
        return result;
    }
}
