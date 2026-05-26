using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace SlideShow;

public sealed class PowerAwakeManager : IDisposable
{
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;

    private readonly ViewerActivityTracker _viewerActivity;
    private readonly System.Threading.Timer _timer;
    private readonly SafeFileHandle _powerRequest;
    private readonly object _gate = new();
    private bool _holdingRequest;

    public PowerAwakeManager(ViewerActivityTracker viewerActivity)
    {
        _viewerActivity = viewerActivity;
        var reason = new ReasonContext
        {
            Version = 0,
            Flags = 1,
            SimpleReasonString = "Slide Show viewer active"
        };
        _powerRequest = PowerCreateRequest(ref reason);
        _timer = new System.Threading.Timer(CheckViewerActivity, null, TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(15));
    }

    public void Dispose()
    {
        _timer.Dispose();
        SetRequestHeld(false);
        _powerRequest.Dispose();
    }

    private void CheckViewerActivity(object? state)
    {
        var hasActiveViewer = _viewerActivity.HasActiveRemoteViewer();
        SetRequestHeld(hasActiveViewer);
        if (hasActiveViewer)
        {
            SetThreadExecutionState(ES_SYSTEM_REQUIRED);
        }
    }

    private void SetRequestHeld(bool shouldHold)
    {
        if (_powerRequest.IsInvalid || _powerRequest.IsClosed)
        {
            return;
        }

        lock (_gate)
        {
            if (shouldHold == _holdingRequest)
            {
                return;
            }

            var success = shouldHold
                ? PowerSetRequest(_powerRequest, PowerRequestType.PowerRequestSystemRequired)
                : PowerClearRequest(_powerRequest, PowerRequestType.PowerRequestSystemRequired);

            if (success)
            {
                _holdingRequest = shouldHold;
            }
        }
    }

    private enum PowerRequestType
    {
        PowerRequestDisplayRequired = 0,
        PowerRequestSystemRequired = 1,
        PowerRequestAwayModeRequired = 2,
        PowerRequestExecutionRequired = 3
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ReasonContext
    {
        public uint Version;
        public uint Flags;
        [MarshalAs(UnmanagedType.LPWStr)]
        public string SimpleReasonString;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern SafeFileHandle PowerCreateRequest(ref ReasonContext context);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PowerSetRequest(SafeFileHandle powerRequest, PowerRequestType requestType);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PowerClearRequest(SafeFileHandle powerRequest, PowerRequestType requestType);

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint esFlags);
}
