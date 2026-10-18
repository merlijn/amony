import {useUrlParam} from "../../api/ReactUtils";
import {Constants, generateRandomSeed, parseResolutionParam, parseThresholdRangeParam, resolutionAsParameter, thresholdRangeAsParameter, useSortParam} from "../../api/Constants";
import * as Popover from "@radix-ui/react-popover";
import './FilterDropdown.scss';
import {MdTune, MdRefresh, MdArrowUpward, MdArrowDownward} from "react-icons/md";
import React, { useEffect, useRef, useState } from "react";
import {Sort, ThresholdDirection, ThresholdFilter} from "../../api/Model";
import {useLocation, useNavigate} from "react-router-dom";
import {buildUrl, copyParams} from "../../api/Util";

const FilterDropDown = (props: { onToggleFilter: (v: boolean) => any}) => {

  const [vqParam, setVqParam]             = useUrlParam("vq", "0")
  const [sortParam, setSortParam]         = useSortParam()
  const [durationParam, setDurationParam] = useUrlParam("d", "-")
  const [uploadParam, setUploadParam]     = useUrlParam("u", "-")
  const [streamableParam, setStreamableParam] = useUrlParam("streamable", "")
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [contentWidth, setContentWidth] = useState(0)

  const navigate = useNavigate()
  const location = useLocation()
  const untaggedSelected      = (new URLSearchParams(location.search).get("untagged") || "").toLowerCase() === "true"
  const nonStreamableSelected = streamableParam.toLowerCase() === "false"

  const toggleUntagged = () => {
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)

    if (untaggedSelected)
      newParams.delete("untagged")
    else {
      newParams.set("untagged", "true")
      newParams.delete("tag")
    }

    navigate(buildUrl("/search", newParams));
  }

  const toggleNonStreamable = () => {
    setStreamableParam(nonStreamableSelected ? "" : "false")
  }

  useEffect(() => {
    const parent = triggerRef.current?.parentElement
    if (!parent) return
    const update = () => setContentWidth(parent.offsetWidth-2)
    update()
    const observer = new ResizeObserver(update)
    observer.observe(parent)
    return () => observer.disconnect()
  }, [])

  return(
    <Popover.Root onOpenChange={props.onToggleFilter}>
      <Popover.Trigger className="filter-dropdown-icon" ref={triggerRef}>
        <MdTune size={25} />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className="filter-dropdown-content" side="bottom" align="end" sideOffset={0} style={{ width: contentWidth || undefined }}>
          <div className = "filter-container">
            <SortSection
              selectedValue = { sortParam }
              onChange      = { setSortParam }
            />
            <ThresholdSelectGroup
              header        = "Resolution"
              options       = { Constants.resolutions.map(option => ({ value: option.value, label: option.label })) }
              selectedValue = { parseResolutionParam(vqParam) }
              onChange      = { value => setVqParam(resolutionAsParameter(value)) }
            />
            <ThresholdSelectGroup
              header        = "Duration"
              options       = { Constants.durationOptions }
              selectedValue = { parseThresholdRangeParam(durationParam) }
              onChange      = { value => setDurationParam(thresholdRangeAsParameter(value)) }
            />
            <ThresholdSelectGroup
              header           = "Upload date"
              options          = { Constants.uploadOptions }
              selectedValue    = { parseThresholdRangeParam(uploadParam) }
              onChange         = { value => setUploadParam(thresholdRangeAsParameter(value)) }
              defaultDirection = "max"
            />
            <CheckboxSelectGroup
              header  = "Other"
              options = { [
                { label: "Untagged",       checked: untaggedSelected,      onChange: toggleUntagged },
                { label: "Non Streamable", checked: nonStreamableSelected, onChange: toggleNonStreamable },
              ] }
            />
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>);
}

type SortSectionProps = {
  selectedValue: Sort;
  onChange: (value: Sort) => void;
};

const SortSection = ({ selectedValue, onChange }: SortSectionProps) => {
  const isRandom = selectedValue.field === "random";

  const selectRandom = () => {
    onChange({ field: "random", seed: generateRandomSeed() });
  };

  const toggleDirection = () => {
    if (selectedValue.field === "random") return
    const newDir = selectedValue.direction === "asc" ? "desc" : "asc"
    onChange({ ...selectedValue, direction: newDir })
  }

  return (
    <div className="filter-section">
      <div className="section-header">Sort by</div>
      {Constants.sortOptions.map((option, index) => {
        const isSelected = !isRandom && selectedValue.field === option.value.field
        const onClick = () => isSelected ? toggleDirection() : onChange(option.value)
        const direction = isSelected ? selectedValue.direction : option.value.direction
        return (
          <div key={`sort-${index}`} className="filter-option" onClick={onClick}>
            <input
              type="radio"
              name="Sort"
              value={option.label}
              checked={isSelected}
              onChange={onClick}
              onClick={(e) => e.stopPropagation()}
            />
            <span className="direction-icon leading">
              {direction === "asc" ? <MdArrowUpward /> : <MdArrowDownward />}
            </span>
            {option.label}
          </div>
        )
      })}
      <div className="filter-option" onClick={selectRandom}>
        <input
          type="radio"
          name="Sort"
          value="Random"
          checked={isRandom}
          onChange={selectRandom}
          onClick={(e) => e.stopPropagation()}
        />
        <span className="direction-icon leading"><MdRefresh /></span>
        Random
      </div>
    </div>
  );
};

type ThresholdSelectProps = {
  header: string;
  options: Array<{ label: string, value: number }>;
  selectedValue: ThresholdFilter | undefined;
  onChange: (value: ThresholdFilter | undefined) => void;
  defaultDirection?: ThresholdDirection;
};

const ThresholdSelectGroup = ({ header, options, selectedValue, onChange, defaultDirection = "min" }: ThresholdSelectProps) => {

  const select = (value: number) => {
    if (value === 0)
      onChange(undefined)
    else if (value === selectedValue?.value)
      onChange({ value, direction: selectedValue.direction === "min" ? "max" : "min" })
    else
      onChange({ value, direction: defaultDirection })
  }

  return (
    <div className="filter-section">
      <div className="section-header">{header}</div>
      {options.map(option => {
        const isSelected = option.value === (selectedValue?.value ?? 0)
        const direction = isSelected ? selectedValue?.direction ?? defaultDirection : defaultDirection
        return (
          <div key={`${header}-${option.value}`} className="filter-option" onClick={() => select(option.value)}>
            <input
              type="radio"
              name={header}
              value={option.label}
              checked={isSelected}
              onChange={() => select(option.value)}
              onClick={(e) => e.stopPropagation()}
            />
            {option.value !== 0 && (
              <span className="threshold-sign">{direction === "min" ? ">" : "<"}</span>
            )}
            {option.label}
          </div>
        )
      })}
    </div>
  );
};

type CheckboxOption = {
  label: string;
  checked: boolean;
  onChange: () => void;
};

type CheckboxSelectProps = {
  header: string;
  options: Array<CheckboxOption>;
};

const CheckboxSelectGroup = ({ header, options }: CheckboxSelectProps) => {
  return (
    <div className="filter-section">
      <div className="section-header">{header}</div>
      {options.map((option, index) => (
        <div key={`${header}-${index}`} className="filter-option" onClick={option.onChange}>
          <input
            type="checkbox"
            checked={option.checked}
            onChange={option.onChange}
            onClick={(e) => e.stopPropagation()}
          />
          {option.label}
        </div>
      ))}
    </div>
  );
};

export default FilterDropDown