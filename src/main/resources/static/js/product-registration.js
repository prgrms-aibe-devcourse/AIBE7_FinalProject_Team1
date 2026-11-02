(() => {
  const UNITLESS_WARRANTY_BASES = new Set(['사용량 기준', '사용 횟수 기준', '특정 날짜까지']);
  const normalized = (value) => String(value ?? '').trim();

  const usesDurationUnit = (basis) => !UNITLESS_WARRANTY_BASES.has(normalized(basis));

  const buildWarrantyTerms = ({ basis, duration, unit, extra }) => {
    const normalizedBasis = normalized(basis);
    const normalizedDuration = normalized(duration);
    const normalizedUnit = normalized(unit);
    const normalizedExtra = normalized(extra);
    let summary = normalizedBasis;

    if (normalizedBasis === '평생 보증') {
      summary = normalizedBasis;
    } else if (normalizedDuration) {
      const suffix = usesDurationUnit(normalizedBasis) ? normalizedUnit : '';
      summary = [normalizedBasis, `${normalizedDuration}${suffix}`].filter(Boolean).join(' ');
    }

    if (!summary) return normalizedExtra || null;
    return normalizedExtra ? `${summary} · ${normalizedExtra}` : summary;
  };

  const warrantyExpiresAt = (basis, value) => normalized(basis) === '평생 보증' ? null : normalized(value) || null;

  const createLatestRequestGate = () => {
    let version = 0;
    return Object.freeze({
      begin: () => ++version,
      invalidate: () => ++version,
      isCurrent: (requestVersion) => requestVersion === version,
    });
  };

  window.PastportProductRegistration = Object.freeze({
    buildWarrantyTerms,
    createLatestRequestGate,
    usesDurationUnit,
    warrantyExpiresAt,
  });
})();
