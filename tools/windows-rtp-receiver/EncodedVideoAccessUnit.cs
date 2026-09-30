internal enum VideoCodec { H264 }
// Owned immutable-by-contract snapshots. No RTP fields escape the adapter.
internal sealed record EncodedVideoAccessUnit(
    ulong SessionId, byte[] AnnexB, long PresentationTimeUs, bool Keyframe,
    byte[] CodecConfiguration, bool Discontinuity, long ReconstructedAtTicks,
    VideoCodec Codec = VideoCodec.H264);

internal interface IEncodedVideoSink
{
    void Submit(EncodedVideoAccessUnit unit);
}
