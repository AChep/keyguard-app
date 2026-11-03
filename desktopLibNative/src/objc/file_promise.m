#import <AppKit/AppKit.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <stdbool.h>
#include <stdint.h>

#include "main_thread.h"

NS_ASSUME_NONNULL_BEGIN

typedef bool (*KGPromiseWrite)(void *, size_t, const char *);
typedef void (*KGPromiseEnded)(void *, bool);
typedef void (*KGPromiseRetired)(void *);

// Mirrors NATIVE_DRAG_MAX_FILES in NativeDrag.kt and MAX_FILES in drag.rs:
// one drag carries 1...64 files.
static const size_t kg_promise_max_files = 64;

@interface KGFilePromiseOffer : NSObject <NSFilePromiseProviderDelegate, NSDraggingSource>
@property(nonatomic, copy) NSArray<NSString *> *names;
@property(nonatomic, assign) uint64_t identifier;
@property(nonatomic, assign, nullable) void *context;
@property(nonatomic, assign) KGPromiseWrite write;
@property(nonatomic, assign) KGPromiseEnded ended;
@property(nonatomic, assign) KGPromiseRetired retired;
@property(nonatomic, copy, nullable) NSArray<NSFilePromiseProvider *> *providers;
@property(nonatomic, assign) bool revoked;
@property(nonatomic, assign) bool closed;
@property(nonatomic, assign) bool endedReported;
@property(nonatomic, assign) bool sessionEnded;
@property(nonatomic, assign) bool accepted;
@property(nonatomic, assign) bool ownershipSettled;
@property(nonatomic, assign) bool pendingRetirement;
@property(nonatomic, assign) NSUInteger activeWrites;
@property(nonatomic, strong, nullable) NSMutableArray<void (^)(void)> *pendingWrites;
- (void)settleOwnership:(bool)owned;
- (void)reportEnded;
- (void)revoke;
- (void)retire;
@end

// Only AppKit-thread code allocates identifiers. They are never reused.
static uint64_t kg_promise_next_identifier = 1;

// AppKit's thread registers and unregisters offers; revoke looks them up from
// any thread. Worker lifetime is separately pinned by activeWrites, so
// retiring an offer never frees a live JNI callback.
static NSMutableDictionary<NSNumber *, KGFilePromiseOffer *> *kg_promise_offers(void) {
    static NSMutableDictionary<NSNumber *, KGFilePromiseOffer *> *offers;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        offers = [NSMutableDictionary new];
    });
    return offers;
}

static void kg_promise_register(KGFilePromiseOffer *offer) {
    NSMutableDictionary<NSNumber *, KGFilePromiseOffer *> *offers = kg_promise_offers();
    @synchronized(offers) {
        offers[@(offer.identifier)] = offer;
    }
}

static void kg_promise_unregister(uint64_t identifier) {
    NSMutableDictionary<NSNumber *, KGFilePromiseOffer *> *offers = kg_promise_offers();
    @synchronized(offers) {
        [offers removeObjectForKey:@(identifier)];
    }
}

static KGFilePromiseOffer *_Nullable kg_promise_lookup(uint64_t identifier) {
    NSMutableDictionary<NSNumber *, KGFilePromiseOffer *> *offers = kg_promise_offers();
    @synchronized(offers) {
        return offers[@(identifier)];
    }
}

// The native authority on promised names. isValidMacFilePromiseNames in
// MacFilePromise.kt mirrors it to fail fast. Names that differ only in case
// would claim one destination on a case-insensitive volume, so they are rejected.
static NSArray<NSString *> *_Nullable kg_promise_names(const char *_Nullable const *_Nullable names, size_t count) {
    if (!names || count == 0 || count > kg_promise_max_files) return nil;
    NSMutableArray<NSString *> *filenames = [NSMutableArray arrayWithCapacity:count];
    NSMutableSet<NSString *> *folded = [NSMutableSet setWithCapacity:count];
    NSCharacterSet *blank = NSCharacterSet.whitespaceAndNewlineCharacterSet;
    for (size_t i = 0; i < count; i++) {
        NSString *filename = names[i] ? [[NSString alloc] initWithUTF8String:names[i]] : nil;
        if (!filename || ![filename stringByTrimmingCharactersInSet:blank].length
            || [filename isEqualToString:@"."] || [filename isEqualToString:@".."]
            || [filename containsString:@"/"]) {
            return nil;
        }
        NSString *key = filename.lowercaseString;
        if ([folded containsObject:key]) return nil;
        [folded addObject:key];
        [filenames addObject:filename];
    }
    return filenames;
}

// Every provider carries its immutable file index from before the drag starts.
static NSUInteger kg_promise_index(NSFilePromiseProvider *provider) {
    id index = provider.userInfo;
    return [index isKindOfClass:NSNumber.class] ? [(NSNumber *)index unsignedIntegerValue] : NSNotFound;
}

static NSError *kg_promise_error(void) {
    return [NSError errorWithDomain:NSCocoaErrorDomain code:NSFileWriteUnknownError userInfo:nil];
}

static NSOperationQueue *kg_promise_write_queue(void) {
    static NSOperationQueue *queue;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        queue = [NSOperationQueue new];
        queue.name = @"Keyguard attachment file promises";
        queue.maxConcurrentOperationCount = 8;
        queue.qualityOfService = NSQualityOfServiceUserInitiated;
    });
    return queue;
}

@implementation KGFilePromiseOffer
- (NSString *)filePromiseProvider:(NSFilePromiseProvider *)provider fileNameForType:(NSString *)fileType {
    (void)fileType;
    NSUInteger index = kg_promise_index(provider);
    return index < self.names.count ? self.names[index] : @"";
}

- (NSOperationQueue *)operationQueueForFilePromiseProvider:(NSFilePromiseProvider *)provider {
    (void)provider;
    return kg_promise_write_queue();
}

- (void)filePromiseProvider:(NSFilePromiseProvider *)provider
         writePromiseToURL:(NSURL *)url
         completionHandler:(void (^)(NSError *_Nullable))completionHandler {
    // NSFilePromiseProvider's delegate is weak. Registry retirement may happen
    // concurrently with worker I/O, so keep this offer alive through completion.
    __attribute__((objc_precise_lifetime)) KGFilePromiseOffer *lifetime = self;
    (void)lifetime;
    if (NSThread.isMainThread) {
        [kg_promise_write_queue() addOperationWithBlock:^{
            [self filePromiseProvider:provider writePromiseToURL:url completionHandler:completionHandler];
        }];
        return;
    }
    NSUInteger index = kg_promise_index(provider);
    void *context;
    @synchronized(self) {
        if (!self.ownershipSettled) {
            if (!self.pendingWrites) self.pendingWrites = [NSMutableArray new];
            // A synchronous accepted drop may request every file before settlement.
            if (self.pendingWrites.count >= kg_promise_max_files) {
                completionHandler(kg_promise_error());
            } else {
                __weak KGFilePromiseOffer *weakOffer = self;
                [self.pendingWrites addObject:^{
                    KGFilePromiseOffer *offer = weakOffer;
                    if (offer) {
                        [offer filePromiseProvider:provider writePromiseToURL:url completionHandler:completionHandler];
                    } else {
                        completionHandler(kg_promise_error());
                    }
                }];
            }
            return;
        }
        if (self.closed || self.revoked || !self.context || index >= self.names.count || self.activeWrites >= 8) {
            completionHandler(kg_promise_error());
            return;
        }
        self.activeWrites++;
        context = self.context;
    }
    bool success = false;
    @try {
        const char *path = url.isFileURL ? url.path.fileSystemRepresentation : NULL;
        success = path && self.write(context, index, path);
    } @catch (NSException *exception) {
        (void)exception;
    }
    // Clear the owned context only after the final in-flight writer finishes.
    void *release = NULL;
    @synchronized(self) {
        self.activeWrites--;
        if (self.closed && self.activeWrites == 0) {
            release = self.context;
            self.context = NULL;
        }
    }
    if (release) self.retired(release);
    completionHandler(success ? nil : kg_promise_error());
}

- (NSDragOperation)draggingSession:(NSDraggingSession *)session
    sourceOperationMaskForDraggingContext:(NSDraggingContext)context {
    (void)session;
    (void)context;
    return NSDragOperationCopy;
}

- (BOOL)ignoreModifierKeysForDraggingSession:(NSDraggingSession *)session {
    (void)session;
    return YES;
}

- (void)draggingSession:(NSDraggingSession *)session endedAtPoint:(NSPoint)point operation:(NSDragOperation)operation {
    __attribute__((objc_precise_lifetime)) KGFilePromiseOffer *lifetime = self;
    (void)lifetime;
    (void)session;
    (void)point;
    self.sessionEnded = true;
    self.accepted = (operation & NSDragOperationCopy) != 0;
    // AppKit can end synchronously inside beginDraggingSession. Until its
    // outcome is settled Rust still owns context, so record without callbacks.
    if (self.ownershipSettled) [self reportEnded];
}

- (void)reportEnded {
    __attribute__((objc_precise_lifetime)) KGFilePromiseOffer *lifetime = self;
    (void)lifetime;
    if (!self.endedReported) {
        self.endedReported = true;
        if (self.context) self.ended(self.context, self.accepted);
    }
    if (!self.accepted) [self retire];
    if (self.closed) kg_promise_unregister(self.identifier);
}

- (void)settleOwnership:(bool)owned {
    __attribute__((objc_precise_lifetime)) KGFilePromiseOffer *lifetime = self;
    (void)lifetime;
    NSArray<void (^)(void)> *writes;
    @synchronized(self) {
        if (self.ownershipSettled) return;
        self.ownershipSettled = true;
        if (!owned) {
            // No reader or JNI callback was admitted during initialization.
            // The caller will free context after the failed startup result.
            self.context = NULL;
            self.closed = true;
        }
        writes = [self.pendingWrites copy];
        self.pendingWrites = nil;
    }
    if (owned) {
        if (self.sessionEnded) [self reportEnded];
        if (self.pendingRetirement) [self retire];
    } else {
        self.providers = nil;
        kg_promise_unregister(self.identifier);
    }
    for (void (^write)(void) in writes) {
        [kg_promise_write_queue() addOperationWithBlock:write];
    }
}

// Any thread. New writes fail from here on; the end report and retirement
// stay on AppKit's thread, which also serializes them with the session end.
- (void)revoke {
    @synchronized(self) {
        if (self.revoked) return;
        self.revoked = true;
    }
    kg_enqueue_on_main(^{
        [self retire];
    });
}

- (void)retire {
    __attribute__((objc_precise_lifetime)) KGFilePromiseOffer *lifetime = self;
    (void)lifetime;
    if (!self.ownershipSettled) {
        self.pendingRetirement = true;
        return;
    }
    if (self.closed) return;
    if (!self.endedReported) {
        self.endedReported = true;
        if (self.context) self.ended(self.context, false);
    }
    void *release = NULL;
    @synchronized(self) {
        self.closed = true;
        if (self.activeWrites == 0) {
            release = self.context;
            self.context = NULL;
        }
    }
    // NSDraggingSession does not own its source. Even a revoked source stays
    // alive until the native session ends; its content callback is already gone.
    if (self.sessionEnded) kg_promise_unregister(self.identifier);
    self.providers = nil;
    if (release) self.retired(release);
}
@end

// A positive handle transfers context ownership to the offer; zero leaves it with Rust.
int64_t kg_file_promise_start(uint64_t window_handle, const char *_Nullable const *_Nullable names, size_t count,
                              void *_Nullable context, KGPromiseWrite _Nullable write,
                              KGPromiseEnded _Nullable ended, KGPromiseRetired _Nullable retired) {
    if (!window_handle || !context || !write || !ended || !retired) return 0;
    @autoreleasepool {
        // Copies the names synchronously; the caller may free them on return.
        NSArray<NSString *> *filenames = kg_promise_names(names, count);
        if (!filenames) return 0;
        __block int64_t handle = 0;
        kg_run_on_main_within(NSEC_PER_SEC, ^{
            KGFilePromiseOffer *offer = nil;
            bool started = false;
            @try {
                NSWindow *window = nil;
                for (NSWindow *candidate in NSApp.windows) {
                    if ((__bridge void *)candidate == (void *)(uintptr_t)window_handle) {
                        window = candidate;
                        break;
                    }
                }
                if (!window || !window.visible || !window.contentView || !(NSEvent.pressedMouseButtons & 1)) return;
                // Handles are positive jlongs.
                if (kg_promise_next_identifier >= INT64_MAX) return;
                offer = [KGFilePromiseOffer new];
                offer.names = filenames;
                offer.identifier = kg_promise_next_identifier++;
                offer.context = context;
                offer.write = write;
                offer.ended = ended;
                offer.retired = retired;
                NSPoint location = window.mouseLocationOutsideOfEventStream;
                NSPoint local = [window.contentView convertPoint:location fromView:nil];
                NSMutableArray<NSFilePromiseProvider *> *providers = [NSMutableArray arrayWithCapacity:filenames.count];
                NSMutableArray<NSDraggingItem *> *items = [NSMutableArray arrayWithCapacity:filenames.count];
                for (NSUInteger index = 0; index < filenames.count; index++) {
                    NSString *filename = filenames[index];
                    UTType *contentType = filename.pathExtension.length
                        ? [UTType typeWithFilenameExtension:filename.pathExtension] : nil;
                    if (!contentType) contentType = UTTypeData;
                    NSFilePromiseProvider *provider = [[NSFilePromiseProvider alloc]
                        initWithFileType:contentType.identifier delegate:offer];
                    provider.userInfo = @(index);
                    [providers addObject:provider];
                    NSDraggingItem *item = [[NSDraggingItem alloc] initWithPasteboardWriter:provider];
                    NSImage *image = [NSWorkspace.sharedWorkspace iconForContentType:contentType];
                    [item setDraggingFrame:NSMakeRect(local.x, local.y, 48, 48) contents:image];
                    [items addObject:item];
                }
                offer.providers = providers;
                NSEvent *event = [NSEvent mouseEventWithType:NSEventTypeLeftMouseDragged location:location
                    modifierFlags:NSEvent.modifierFlags timestamp:NSProcessInfo.processInfo.systemUptime
                    windowNumber:window.windowNumber context:nil eventNumber:0 clickCount:1 pressure:1];
                if (!event) return;
                kg_promise_register(offer);
                NSDraggingSession *session = [window.contentView beginDraggingSessionWithItems:items event:event
                                                                                        source:offer];
                session.draggingFormation = NSDraggingFormationPile;
                started = session != nil;
            } @catch (NSException *exception) {
                (void)exception;
            } @finally {
                [offer settleOwnership:started];
                if (started) handle = (int64_t)offer.identifier;
            }
        });
        return handle;
    }
}

// Any thread. Unknown, stale or already revoked handles are ignored.
void kg_file_promise_revoke(int64_t handle) {
    if (handle <= 0) return;
    @autoreleasepool {
        [kg_promise_lookup((uint64_t)handle) revoke];
    }
}

NS_ASSUME_NONNULL_END
