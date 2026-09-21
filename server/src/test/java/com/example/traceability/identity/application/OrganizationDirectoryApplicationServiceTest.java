package com.example.traceability.identity.application;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.dto.OrganizationSummaryResponse;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("组织目录最小只读应用服务单元测试")
class OrganizationDirectoryApplicationServiceTest {

    @Mock
    private OrganizationMapper organizationMapper;

    @InjectMocks
    private OrganizationDirectoryApplicationService service;

    private TraceSecurityPrincipal operatorPrincipal;
    private TraceSecurityPrincipal platformPrincipal;

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "企业操作员", "{noop}pwd",
                10L, "ORG_FISHERY_01", "第一远洋捕捞公司", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );
        platformPrincipal = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                1L, "ORG_PLATFORM", "溯源管理中心", "PLATFORM",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );
    }

    private Organization organization(Long id) {
        Organization org = new Organization();
        org.setId(id);
        org.setOrgNo("ORG_" + id);
        org.setName("组织" + id);
        org.setOrgType("PROCESSOR");
        org.setCreditCode("SECRET_CREDIT_" + id);
        org.setStatus("ACTIVE");
        org.setVersion(3L);
        org.setIsDeleted(0);
        return org;
    }

    @Test
    @DisplayName("企业用户读取本组织 - 返回白名单摘要")
    void ownOrganization_ReturnsSummary() {
        when(organizationMapper.selectById(10L)).thenReturn(organization(10L));

        OrganizationSummaryResponse response = service.getOrganization(10L, operatorPrincipal);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.orgNo()).isEqualTo("ORG_10");
        assertThat(response.name()).isEqualTo("组织10");
        assertThat(response.orgType()).isEqualTo("PROCESSOR");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("白名单摘要结构不包含统一社会信用代码、版本号等敏感或内部字段")
    void summary_DoesNotDeclareSensitiveFields() {
        List<String> components = Arrays.stream(OrganizationSummaryResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("id", "orgNo", "name", "orgType", "status");
    }

    @Test
    @DisplayName("企业用户读取其他组织 - 查询数据库前直接返回 403 ORG_SCOPE_DENIED")
    void otherOrganization_ForbiddenWithoutLookup() {
        assertThatThrownBy(() -> service.getOrganization(20L, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_SCOPE_DENIED");
                });

        verify(organizationMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("PLATFORM 作用域可读取任意组织摘要")
    void platformScope_ReadsAnyOrganization() {
        when(organizationMapper.selectById(20L)).thenReturn(organization(20L));

        OrganizationSummaryResponse response = service.getOrganization(20L, platformPrincipal);

        assertThat(response.id()).isEqualTo(20L);
    }

    @Test
    @DisplayName("组织不存在或已逻辑删除 - 返回 404 RESOURCE_NOT_FOUND")
    void missingOrganization_NotFound() {
        when(organizationMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.getOrganization(99L, platformPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
