using System.Buffers.Binary;
using System.Diagnostics;

internal static class ReceiverSelfTest
{
    private sealed class Sink : IEncodedVideoSink
    {
        public List<EncodedVideoAccessUnit> Units { get; } = [];
        public void Submit(EncodedVideoAccessUnit unit) => Units.Add(unit);
    }
    internal static byte[] Packet(ushort sequence, uint timestamp, long pts, byte[] nal, bool marker, bool key, uint stream = 9)
    {
        var packet = new byte[36 + nal.Length]; packet[0] = 0x90; packet[1] = (byte)(96 | (marker ? 128 : 0));
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2), sequence);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4), timestamp);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(8), stream);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(12), 0xbede);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(14), 5);
        packet[16] = 0x17; BinaryPrimitives.WriteInt64BigEndian(packet.AsSpan(17), pts);
        packet[25] = 0x23; packet[30] = 0x30; packet[31] = (byte)(key ? 3 : 0);
        nal.CopyTo(packet, 36); return packet;
    }
    public static int Run()
    {
        var sink = new Sink(); var receiver = new RtpH264Receiver(sink);
        void Send(ushort sequence, uint timestamp, long pts, byte[] nal, bool marker, bool key, uint stream = 9)
        {
            var packet = Packet(sequence, timestamp, pts, nal, marker, key, stream);
            receiver.Process(packet, packet.Length, Stopwatch.GetTimestamp());
            Array.Fill(packet, (byte)0); // publication must survive receive-buffer reuse
        }
        void Require(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
        Send(1, 0, 1_000_000, [0x67, 0x11], false, true);
        Send(2, 0, 1_000_000, [0x68, 0x22], false, true);
        Send(3, 0, 1_000_000, [0x65, 0x33], true, true);
        Require(sink.Units.Count == 1 && sink.Units[0].Keyframe && sink.Units[0].Discontinuity, "Startup/config/IDR");
        var first = sink.Units[0];
        Require(first.PresentationTimeUs == 1_000_000 && first.CodecConfiguration.Length == 12 && first.AnnexB[4] == 0x67, "PTS/config/ownership");
        Send(4, 3000, 1_033_333, [0x41, 0x44], true, false);
        Require(sink.Units.Count == 2 && !sink.Units[1].Discontinuity && sink.Units[1].CodecConfiguration.Length == 12, "Cached config");
        Send(6, 6000, 1_066_667, [0x41, 0x55], true, false);
        Require(sink.Units.Count == 2, "Sequence-gap AU must be discarded");
        Send(7, 9000, 1_100_000, [0x41, 0x66], true, false);
        Require(sink.Units.Count == 3 && sink.Units[2].Discontinuity, "Loss discontinuity");
        Send(8, 12000, 1_133_333, [0x7c, 0x85, 0x11, 0x22], false, true);
        Send(9, 12000, 1_133_333, [0x7c, 0x45, 0x33, 0x44], true, true);
        Require(sink.Units[^1].AnnexB.SequenceEqual(new byte[] {0,0,0,1,0x65,0x11,0x22,0x33,0x44}), "FU-A reconstruction");
        Send(1, 0, 100, [0x65, 0x88], true, true, 10);
        Require(sink.Units[^1].SessionId != first.SessionId && sink.Units[^1].Discontinuity && sink.Units[^1].CodecConfiguration.Length == 0, "Restart config isolation");
        Require(first.AnnexB[4] == 0x67 && first.AnnexB[^1] == 0x33, "Reassembly reuse changed owned AU");
        Console.WriteLine("PASS: exact PTS, owned AU, cached SPS/PPS, FU-A, sequence loss/discontinuity, stream restart isolation.");
        return 0;
    }
}
