type YearlySelectorProps = {
  value: number;
  years: number[];
  onChange: (year: number) => void;
};

export function YearlySelector({ value, years, onChange }: YearlySelectorProps) {
  const options = years.length > 0 ? years : [value];
  return (
    <label className="yearly-selector">
      <span>回顾年份</span>
      <select value={value} onChange={(event) => onChange(Number(event.target.value))}>
        {options.map((year) => (
          <option key={year} value={year}>
            {year} 年
          </option>
        ))}
      </select>
    </label>
  );
}
