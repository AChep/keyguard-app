#import "../../rust/crates/keyguard-launch-at-login-core/src/macos.m"
#include <assert.h>
#include <string.h>

@interface FixtureService : NSObject <KGLoginService>
@property(nonatomic) SMAppServiceStatus status;
@property(nonatomic) SMAppServiceStatus registeredStatus;
@property(nonatomic) NSUInteger registrations;
@property(nonatomic) NSUInteger removals;
@property(nonatomic) BOOL fail;
@property(nonatomic) BOOL omitError;
@end

@implementation FixtureService
- (BOOL)registerAndReturnError:(NSError **)error {
    self.registrations++;
    if (self.fail) {
        if (!self.omitError) *error = [NSError errorWithDomain:@"fixture" code:42
            userInfo:@{NSLocalizedDescriptionKey: @"permission denied by fixture"}];
        return NO;
    }
    self.status = self.registeredStatus;
    return YES;
}
- (BOOL)unregisterAndReturnError:(NSError **)error {
    self.removals++;
    if (self.fail) {
        if (!self.omitError) *error = [NSError errorWithDomain:@"fixture" code:43
            userInfo:@{NSLocalizedDescriptionKey: @"unregister denied by fixture"}];
        return NO;
    }
    self.status = SMAppServiceStatusNotRegistered;
    return YES;
}
@end

static int settingsCalls = 0;
static void settings(void) { settingsCalls++; }

int main(void) {
    @autoreleasepool {
        FixtureService *service = [FixtureService new];
        char diagnostic[128] = {0};
        const SMAppServiceStatus states[] = {SMAppServiceStatusEnabled, SMAppServiceStatusNotRegistered,
            SMAppServiceStatusRequiresApproval, SMAppServiceStatusNotFound, (SMAppServiceStatus)99};
        const int32_t expected[] = {0, 1, 2, 4, 6};
        for (NSUInteger i = 0; i < 5; i++) {
            service.status = states[i];
            assert(kg_lal_perform(service, 0, diagnostic, sizeof(diagnostic), settings) == expected[i]);
            assert(service.status == states[i]);
        }
        assert(service.registrations == 0 && service.removals == 0);
        for (NSUInteger i = 0; i < 2; i++) {
            service.status = i == 0 ? SMAppServiceStatusEnabled : SMAppServiceStatusRequiresApproval;
            assert(kg_lal_perform(service, 1, diagnostic, sizeof(diagnostic), settings) == (i == 0 ? 0 : 2));
        }
        assert(service.registrations == 0);
        service.status = SMAppServiceStatusNotRegistered;
        service.registeredStatus = SMAppServiceStatusRequiresApproval;
        assert(kg_lal_perform(service, 1, diagnostic, sizeof(diagnostic), settings) == 2);
        assert(service.registrations == 1);
        // OS approval/revocation is discovered on the next query without mutation.
        service.status = SMAppServiceStatusEnabled;
        assert(kg_lal_perform(service, 0, diagnostic, sizeof(diagnostic), settings) == 0);
        service.status = SMAppServiceStatusRequiresApproval;
        assert(kg_lal_perform(service, 0, diagnostic, sizeof(diagnostic), settings) == 2);
        assert(kg_lal_perform(service, 2, diagnostic, sizeof(diagnostic), settings) == 1);
        assert(kg_lal_perform(service, 2, diagnostic, sizeof(diagnostic), settings) == 1);
        assert(service.removals == 1);
        service.fail = YES;
        assert(kg_lal_perform(service, 1, diagnostic, sizeof(diagnostic), settings) == -3);
        assert(strcmp(diagnostic, "permission denied by fixture") == 0);
        assert(service.status == SMAppServiceStatusNotRegistered);
        service.status = SMAppServiceStatusEnabled;
        assert(kg_lal_perform(service, 2, diagnostic, sizeof(diagnostic), settings) == -3);
        assert(strcmp(diagnostic, "unregister denied by fixture") == 0);
        assert(service.status == SMAppServiceStatusEnabled);
        service.status = SMAppServiceStatusNotRegistered;
        service.omitError = YES;
        assert(kg_lal_perform(service, 1, diagnostic, sizeof(diagnostic), settings) == -3);
        assert(strcmp(diagnostic, "ServiceManagement failed") == 0);
        char bounded[3] = {'x', 'x', 'x'};
        assert(kg_lal_perform(service, 1, bounded, sizeof(bounded), settings) == -3);
        assert(bounded[2] == '\0');
        service.fail = NO;
        service.registeredStatus = SMAppServiceStatusEnabled;
        assert(kg_lal_perform(service, 1, diagnostic, sizeof(diagnostic), settings) == 0);
        assert(kg_lal_perform(service, 3, diagnostic, sizeof(diagnostic), settings) == 0);
        assert(settingsCalls == 1);

        NSAppleEventDescriptor *event = [NSAppleEventDescriptor appleEventWithEventClass:'aevt'
            eventID:'oapp' targetDescriptor:nil returnID:-1 transactionID:0];
        assert(!kg_lal_is_login_event(nil));
        assert(!kg_lal_is_login_event(event));
        [event setParamDescriptor:[NSAppleEventDescriptor descriptorWithEnumCode:'lgit'] forKeyword:'prdt'];
        assert(kg_lal_is_login_event(event));
        [event setParamDescriptor:[NSAppleEventDescriptor descriptorWithEnumCode:'test'] forKeyword:'prdt'];
        assert(!kg_lal_is_login_event(event));
        puts("ServiceManagement operation, failure, refresh, cleanup, and launch-event fixtures passed");
    }
    return 0;
}
