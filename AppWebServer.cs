using System.Net;
using System.Net.NetworkInformation;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace SlideShow;

public sealed class AppWebServer : IAsyncDisposable
{
    private readonly AppState _state;
    private readonly Func<string?, Task<string?>> _chooseFolderAsync;
    private readonly Func<Task> _openSlideshowWindowAsync;
    private WebApplication? _app;

    public AppWebServer(AppState state, Func<string?, Task<string?>> chooseFolderAsync, Func<Task> openSlideshowWindowAsync)
    {
        _state = state;
        _chooseFolderAsync = chooseFolderAsync;
        _openSlideshowWindowAsync = openSlideshowWindowAsync;
    }

    public async Task StartAsync()
    {
        var preferredPort = _state.GetSettings().Port;
        for (var attempt = 0; attempt < 20; attempt++)
        {
            var port = preferredPort + attempt;
            var builder = WebApplication.CreateBuilder(new WebApplicationOptions
            {
                Args = [],
                ApplicationName = typeof(Program).Assembly.FullName,
                ContentRootPath = AppContext.BaseDirectory
            });

            builder.Logging.ClearProviders();
            builder.Logging.SetMinimumLevel(LogLevel.None);
            builder.Services.Configure<JsonOptions>(options =>
            {
                options.SerializerOptions.PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.CamelCase;
            });
            builder.WebHost.UseKestrel(options =>
            {
                options.AddServerHeader = false;
            });
            builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

            var app = builder.Build();
            Configure(app);

            try
            {
                await app.StartAsync();
                _app = app;
                _state.UpdatePort(port);
                return;
            }
            catch
            {
                await app.DisposeAsync();
            }
        }
    }

    public Task StopAsync() => _app?.StopAsync() ?? Task.CompletedTask;

    public async ValueTask DisposeAsync()
    {
        if (_app is not null)
        {
            await _app.DisposeAsync();
        }
    }

    private void Configure(WebApplication app)
    {
        var webRoot = Path.Combine(AppContext.BaseDirectory, "wwwroot");
        var assetsRoot = Path.Combine(webRoot, "assets");
        var settingsPage = Path.Combine(webRoot, "pages", "settings.html");
        var slideshowPage = Path.Combine(webRoot, "pages", "slideshow.html");

        if (Directory.Exists(assetsRoot))
        {
            app.UseStaticFiles(new StaticFileOptions
            {
                RequestPath = "/assets",
                FileProvider = new PhysicalFileProvider(assetsRoot),
                OnPrepareResponse = context =>
                {
                    context.Context.Response.Headers["Cache-Control"] = "no-store, no-cache, must-revalidate";
                    context.Context.Response.Headers["Pragma"] = "no-cache";
                    context.Context.Response.Headers["Expires"] = "0";
                }
            });
        }

        app.MapGet("/", (HttpContext context) =>
        {
            return IsLocalRequest(context)
                ? NoCacheFile(context, settingsPage, "text/html")
                : NoCacheFile(context, slideshowPage, "text/html");
        });

        app.MapGet("/settings", (HttpContext context) =>
        {
            if (IsLocalRequest(context))
            {
                return NoCacheFile(context, settingsPage, "text/html");
            }

            AddNoCacheHeaders(context);
            return Results.Redirect("/show");
        });

        app.MapGet("/show", (HttpContext context) => NoCacheFile(context, slideshowPage, "text/html"));
        app.MapGet("/api/state", (HttpContext context) => Results.Json(_state.GetSnapshot(IsLocalRequest(context))));
        app.MapGet("/api/images", (HttpContext context) =>
        {
            var shuffle = !string.Equals(context.Request.Query["shuffle"], "false", StringComparison.OrdinalIgnoreCase);
            return Results.Json(_state.GetImages(shuffle));
        });

        app.MapGet("/image/{id:int}", (HttpContext context, int id) =>
        {
            var image = _state.GetImage(id);
            if (image is null || !File.Exists(image.Path))
            {
                return Results.NotFound();
            }

            context.Response.Headers["Cache-Control"] = "no-store, no-cache, must-revalidate";
            context.Response.Headers["Pragma"] = "no-cache";
            context.Response.Headers["Expires"] = "0";
            return Results.File(image.Path, ImageCatalog.GetContentType(image.Path), enableRangeProcessing: true);
        });

        app.MapPost("/api/settings", (HttpContext context, SettingsUpdateDto update) =>
        {
            if (!IsLocalRequest(context))
            {
                return Results.StatusCode(StatusCodes.Status403Forbidden);
            }

            _state.UpdateSettings(update);
            StartupManager.SetEnabled(_state.GetSettings().StartAtLogin);
            return Results.Json(_state.GetSnapshot(true));
        });

        app.MapPost("/api/playback-settings", (PlaybackSettingsUpdateDto update) =>
        {
            _state.UpdatePlaybackSettings(update);
            return Results.Json(_state.GetSnapshot(false));
        });

        app.MapPost("/api/viewer-heartbeat", (HttpContext context, ViewerHeartbeatDto? heartbeat) =>
        {
            if (!IsLocalRequest(context))
            {
                var viewerKey = GetViewerKey(context);
                if (heartbeat?.Active == false)
                {
                    _state.ClearRemoteViewer(viewerKey);
                }
                else
                {
                    _state.RecordRemoteViewer(viewerKey);
                }
            }

            return Results.Ok();
        });

        app.MapPost("/api/choose-folder", async (HttpContext context) =>
        {
            if (!IsLocalRequest(context))
            {
                return Results.StatusCode(StatusCodes.Status403Forbidden);
            }

            var folder = await _chooseFolderAsync(_state.GetSettings().FolderPath);
            if (folder is not null)
            {
                _state.UpdateSettings(new SettingsUpdateDto { FolderPath = folder });
            }

            return Results.Json(_state.GetSnapshot(true));
        });

        app.MapPost("/api/open-slideshow-window", async (HttpContext context) =>
        {
            if (!IsLocalRequest(context))
            {
                return Results.StatusCode(StatusCodes.Status403Forbidden);
            }

            await _openSlideshowWindowAsync();
            return Results.Ok();
        });

        app.MapPost("/api/rescan", (HttpContext context) =>
        {
            if (!IsLocalRequest(context))
            {
                return Results.StatusCode(StatusCodes.Status403Forbidden);
            }

            _state.Rescan();
            return Results.Json(_state.GetSnapshot(true));
        });
    }

    private static bool IsLocalRequest(HttpContext context)
    {
        var remote = context.Connection.RemoteIpAddress;
        if (remote is null)
        {
            return false;
        }

        if (remote.IsIPv4MappedToIPv6)
        {
            remote = remote.MapToIPv4();
        }

        return IPAddress.IsLoopback(remote) || IsLocalMachineAddress(remote);
    }

    private static bool IsLocalMachineAddress(IPAddress remote)
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(address => address.Address)
            .Select(address => address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address)
            .Any(address => address.Equals(remote));
    }

    private static string GetViewerKey(HttpContext context)
    {
        var remote = context.Connection.RemoteIpAddress;
        if (remote?.IsIPv4MappedToIPv6 == true)
        {
            remote = remote.MapToIPv4();
        }

        return remote?.ToString() ?? "unknown";
    }

    private static IResult NoCacheFile(HttpContext context, string path, string contentType)
    {
        AddNoCacheHeaders(context);
        return Results.File(path, contentType);
    }

    private static void AddNoCacheHeaders(HttpContext context)
    {
        context.Response.Headers["Cache-Control"] = "no-store, no-cache, must-revalidate";
        context.Response.Headers["Pragma"] = "no-cache";
        context.Response.Headers["Expires"] = "0";
    }
}
