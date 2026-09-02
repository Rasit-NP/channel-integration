package com.channel.integration.port;

import java.util.List;

import com.channel.integration.domain.MappingSnapshot;
import com.channel.integration.domain.SupplierCode;

/**
 * 공급사 코드와 내부 식별자의 매핑 저장소.
 *
 * <p>이 시스템이 저장하는 것은 이 매핑뿐이다. 요금·재고의 원본은 외부에 있으므로 저장하지
 * 않는다.
 */
public interface MappingRepository {

    /**
     * 공급사 숙소 목록을 매핑에 반영한다.
     *
     * <p><b>이미 있는 코드의 내부 식별자는 그대로 둔다.</b> 같은 공급사 상품이 언제 조회해도
     * 같은 내부 식별자로 돌아와야 하기 때문이다. 새로 나타난 코드에만 식별자를 부여한다.
     *
     * <p>넘긴 {@link SupplierProperty} 에서 <b>코드만 읽는다.</b> 숙소명·객실 타입명·최대 수용
     * 인원은 저장하지 않는다. 그 값들은 재고·요금 응답에 매번 실려 오므로, 여기에 복사해 두면
     * 같은 값의 출처가 둘이 되고 언젠가 어긋난다.
     *
     * <p>목록에서 사라진 코드를 지우지는 않는다. 한 번 부여한 내부 식별자가 다른 곳에서
     * 참조되고 있을 수 있고, 공급사 목록 API 의 일시적 누락과 실제 철수를 구분할 방법이 없다.
     */
    void register(SupplierCode supplier, List<SupplierProperty> properties);

    /**
     * 매핑 전체를 한 번에 읽는다.
     *
     * <p>검색은 시작할 때 "무엇을 물어볼지"를, 응답을 받은 뒤엔 "그게 우리 쪽 무엇인지"를
     * 알아야 한다. 매번 DB 에 묻는 대신 한 번 읽어 사본으로 들고 쓴다.
     */
    MappingSnapshot load();
}
