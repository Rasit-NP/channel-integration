package com.channel.integration.port;

import java.util.List;

import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.domain.SupplierCode;

/**
 * 공급사 연동 경계.
 *
 * <p>공급사를 추가할 때 이 인터페이스의 구현체만 추가하면 되도록 설계한다. 검색 로직은 등록된
 * 구현체 목록을 순회할 뿐 각 공급사를 알지 못한다.
 *
 * <p>구현체가 지켜야 하는 것:
 * <ul>
 *   <li>공급사 고유 요청/응답 DTO 를 자기 패키지 밖으로 노출하지 않는다.</li>
 *   <li>실패를 예외로 던지지 않는다. 어떤 경우에도 {@link SupplierFetchResult} 를 돌려준다.
 *       상태 코드로 알리든 응답 본문 코드로 알리든, 같은 {@link FailureReason} 으로 정규화한다.</li>
 *   <li>요금은 표준 형태로 변환해 돌려준다. 식별자 매핑과 재고 판정에는 관여하지 않는다.</li>
 * </ul>
 */
public interface SupplierAdapter {

    /** 이 어댑터가 담당하는 공급사. 매핑 저장과 응답의 출처 표기에 쓴다. */
    SupplierCode supplier();

    /**
     * 한 번에 조회할 수 있는 숙소 코드 수의 상한.
     *
     * <p>묶어 호출하는 것은 공급사의 제약이지만, <b>나누는 일은 application 계층이 한다.</b>
     * 어댑터는 자기 한계를 선언만 하고, 검색 로직이 그 값에 맞춰 나눈다. 한계가 다른 공급사가
     * 들어와도 검색 로직을 고치지 않기 위해서다.
     */
    int maxBatchSize();

    /**
     * 공급사가 취급하는 숙소·객실 타입 전체 목록. 조건 파라미터가 없는 정적 콘텐츠다.
     * 매핑을 만들 때 쓴다.
     */
    SupplierFetchResult<List<SupplierProperty>> fetchProperties();

    /**
     * 숙소 코드 묶음에 대한 재고·요금.
     *
     * @param propertyCodes 조회할 숙소 코드. {@link #maxBatchSize()} 이하로 전달되어야 한다.
     * @param criteria      날짜와 인원
     */
    SupplierFetchResult<List<SupplierOffer>> fetchOffers(
            List<String> propertyCodes, SearchCriteria criteria);
}
