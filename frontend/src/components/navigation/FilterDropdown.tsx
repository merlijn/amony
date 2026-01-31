import {useUrlParam} from "../../api/ReactUtils";
import {Constants, durationAsParam, parseDurationParam, useSortParam} from "../../api/Constants";
import {DropDown} from "../common/DropDown";
import {MdTune} from "react-icons/md";
import _ from "lodash";
import React from "react";

const FilterDropDown = (props: { onToggleFilter: (v: boolean) => any}) => {

  const [vqParam, setVqParam]             = useUrlParam("vq", "0")
  const [sortParam, setSortParam]         = useSortParam()
  const [durationParam, setDurationParam] = useUrlParam("d", "-")
  const [uploadParam, setUploadParam]     = useUrlParam("u", "-")

  return(
    <div className = "filter-dropdown-container">

      <DropDown
        toggleIcon = { <MdTune className="filter-dropdown-icon" /> }
        hideOnClick = { false }
        onToggle = { props.onToggleFilter }
        contentClassName = "filter-dropdown-content">
        <div className = "filter-container">
          <RadioSelectGroup
            header        = "Sort"
            options       = { Constants.sortOptions }
            selectedValue = { sortParam }
            onChange      = { setSortParam }
          />
          <RadioSelectGroup
            header        = "Resolution"
            options       = { Constants.resolutions.map(option => ({ label: option.label, value: option.value.toString() })) }
            selectedValue = { vqParam }
            onChange      = { value => setVqParam(value) }
          />
          <RadioSelectGroup
            header        = "Duration"
            options       = { Constants.durationOptions }
            selectedValue = { parseDurationParam(durationParam) }
            onChange      = { value => setDurationParam(durationAsParam(value)) }
          />
          <RadioSelectGroup
            header        = "Upload date"
            options       = { Constants.uploadOptions }
            selectedValue = { parseDurationParam(durationParam)}
            onChange      = { value => setDurationParam(durationAsParam(value)) }
          />
        </div>
      </DropDown>
    </div>);
}

type RadioSelectProps<T> = {
  header: string;
  options: Array<{ label: string, value: T }>;
  selectedValue: T;
  onChange: (value: T) => void;
};

const RadioSelectGroup = <T,>({ header, options, selectedValue, onChange }: RadioSelectProps<T>) => {
  return (
    <div className="filter-section">
      <div className="section-header">{header}</div>
      {options.map((option, index) => (
        <div key={`${header}-${index}`} className="filter-option" onClick={() => onChange(option.value)}>
          <input
            type="radio"
            name={header}
            value={option.label}
            checked={_.isEqual(selectedValue, option.value)}
            onChange={() => onChange(option.value)}
          />
          {option.label}
        </div>
      ))}
    </div>
  );
};

export default FilterDropDown