using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace SlideShow;

public sealed class DiscoveryService : IAsyncDisposable
{
    private const int DiscoveryPort = 51778;
    private const string Probe = "SLIDE_SHOW_DISCOVER_V1";

    private readonly AppState _state;
    private readonly CancellationTokenSource _shutdown = new();
    private UdpClient? _listener;
    private Task? _listenTask;

    public DiscoveryService(AppState state)
    {
        _state = state;
    }

    public void Start()
    {
        if (_listenTask is not null)
        {
            return;
        }

        _listener = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort))
        {
            EnableBroadcast = true
        };
        _listenTask = Task.Run(ListenAsync);
    }

    public async Task StopAsync()
    {
        _shutdown.Cancel();
        _listener?.Close();

        if (_listenTask is not null)
        {
            try
            {
                await _listenTask;
            }
            catch
            {
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _listener?.Dispose();
        _shutdown.Dispose();
    }

    private async Task ListenAsync()
    {
        while (!_shutdown.IsCancellationRequested)
        {
            try
            {
                var result = await _listener!.ReceiveAsync(_shutdown.Token);
                var message = Encoding.UTF8.GetString(result.Buffer);
                if (!string.Equals(message, Probe, StringComparison.Ordinal))
                {
                    continue;
                }

                var payload = JsonSerializer.Serialize(new DiscoveryResponse(
                    "SlideShow",
                    1,
                    Environment.MachineName,
                    _state.Port,
                    "/api/state",
                    "/api/images",
                    "/api/playback-settings",
                    "/show"));

                var bytes = Encoding.UTF8.GetBytes(payload);
                await _listener.SendAsync(bytes, result.RemoteEndPoint, _shutdown.Token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
            }
        }
    }
}

public sealed record DiscoveryResponse(
    string Service,
    int Version,
    string Name,
    int Port,
    string StatePath,
    string ImagesPath,
    string PlaybackSettingsPath,
    string SlideshowPath);
