#import <UIKit/UIKit.h>
#import <unistd.h>
#import <fcntl.h>

extern void aot_ios_entry(void *argv);

static UITextView *gLogView;

@interface AotViewController : UIViewController
@end

static void aot_start_stdout_pump(int readFd)
{
    dispatch_source_t src = dispatch_source_create(DISPATCH_SOURCE_TYPE_READ, (uintptr_t)readFd, 0,
                                                  dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0));
    dispatch_source_set_event_handler(src, ^{
        char buf[4096];
        ssize_t n = read(readFd, buf, sizeof(buf) - 1);
        if (n <= 0) {
            return;
        }
        buf[n] = 0;
        NSString *chunk = [NSString stringWithUTF8String:buf];
        if (!chunk) {
            chunk = [[NSString alloc] initWithBytes:buf length:(NSUInteger)n encoding:NSISOLatin1StringEncoding];
        }
        dispatch_async(dispatch_get_main_queue(), ^{
            if (!gLogView || !chunk) {
                return;
            }
            gLogView.text = [gLogView.text stringByAppendingString:chunk];
            NSRange end = NSMakeRange(gLogView.text.length, 0);
            [gLogView scrollRangeToVisible:end];
        });
    });
    dispatch_resume(src);
}

@implementation AotViewController

- (void)viewDidLoad
{
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];

    gLogView = [[UITextView alloc] initWithFrame:self.view.bounds];
    gLogView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    gLogView.backgroundColor = [UIColor blackColor];
    gLogView.textColor = [UIColor colorWithRed:0.4 green:1.0 blue:0.6 alpha:1.0];
    gLogView.font = [UIFont fontWithName:@"Menlo" size:11];
    gLogView.editable = NO;
    gLogView.text = @"[aot] starting\n";
    [self.view addSubview:gLogView];

    int fds[2];
    if (pipe(fds) == 0) {
        fflush(stdout);
        dup2(fds[1], STDOUT_FILENO);
        close(fds[1]);
        int fl = fcntl(fds[0], F_GETFL, 0);
        fcntl(fds[0], F_SETFL, fl | O_NONBLOCK);
        setvbuf(stdout, NULL, _IONBF, 0);
        aot_start_stdout_pump(fds[0]);
    }

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        aot_ios_entry(NULL);
        fflush(stdout);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(200 * NSEC_PER_MSEC)),
                       dispatch_get_main_queue(), ^{
            gLogView.text = [gLogView.text stringByAppendingString:@"\n[aot] finished\n"];
            NSRange end = NSMakeRange(gLogView.text.length, 0);
            [gLogView scrollRangeToVisible:end];
        });
    });
}

@end

@interface AotAppDelegate : UIResponder <UIApplicationDelegate>
@property (strong, nonatomic) UIWindow *window;
@end

@implementation AotAppDelegate

- (BOOL)application:(UIApplication *)app didFinishLaunchingWithOptions:(NSDictionary *)opts
{
    self.window = [[UIWindow alloc] initWithFrame:[[UIScreen mainScreen] bounds]];
    self.window.rootViewController = [[AotViewController alloc] init];
    [self.window makeKeyAndVisible];
    return YES;
}

@end

int main(int argc, char *argv[])
{
    NSString *cls = NSStringFromClass([AotAppDelegate class]);
    return UIApplicationMain(argc, argv, nil, cls);
}