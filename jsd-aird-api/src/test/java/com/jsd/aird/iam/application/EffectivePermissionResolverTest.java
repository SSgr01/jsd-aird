package com.jsd.aird.iam.application;

import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EffectivePermissionResolverTest {
    final IamStore store=mock(IamStore.class);
    final UUID org=UUID.randomUUID(),userId=UUID.randomUUID(),roleId=UUID.randomUUID();
    final User user=new User(userId,org,"luoan","罗安",null,null,null,roleId,"COORDINATOR","资料协调","ACTIVE",false,"hash",0,null,0,null);
    final Role role=new Role(roleId,org,"COORDINATOR","资料协调",false,true,0);

    @Test void explicitViewDenialSuppressesMenuAndDoesNotAcquirePermissionFromItsGroup() {
        when(store.userRoles(org,userId)).thenReturn(List.of(role));
        when(store.roleMenus(org,roleId)).thenReturn(List.of("workbench","quality","quality.view","ai","ai.qa"));
        when(store.userOperationOverrides(org,userId)).thenReturn(List.of(
            new Binding("quality.view.view","DENY","ALL",List.of()),new Binding("quality.view.update","ALLOW","ALL",List.of())));
        var result=new EffectivePermissionResolver(store,false).resolve(org,user);
        assertThat(result.menuKeys()).contains("ai.qa").doesNotContain("quality.view");
        assertThat(result.permissionCodes()).contains("quality.view.update","ai.use").doesNotContain("quality.view","quality.view.view");
    }

    @Test void permissionLevelsRemainIndependentFromRemovedNavigation() {
        when(store.userRoles(org,userId)).thenReturn(List.of(role));
        when(store.roleMenus(org,roleId)).thenReturn(List.of("quality","quality.view"));
        when(store.userMenuOverrides(org,userId)).thenReturn(List.of(new MenuOverride("quality.view","DENY")));
        when(store.userOperationOverrides(org,userId)).thenReturn(List.of(new Binding("quality.view.update","ALLOW","SELF",List.of())));
        var result=new EffectivePermissionResolver(store,false).resolve(org,user);
        assertThat(result.menuKeys()).doesNotContain("quality.view");
        assertThat(result.permissionCodes()).contains("quality.view.update").doesNotContain("quality.view","quality.view.view");
    }

    @Test void groupsAloneNeverGrantDataAccess() {
        assertThat(MenuViewPermissions.impliedBy(Set.of("quality","project","data","ai","inventory"))).isEmpty();
        assertThat(MenuViewPermissions.impliedBy(Set.of("quality.view"))).contains("quality.view","quality.view.view");
    }
}
