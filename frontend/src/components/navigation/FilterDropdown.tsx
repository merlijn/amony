import {useUrlParam} from "../../api/ReactUtils";
import {Constants, rangeAsParameter, parseDurationParam, useSortParam, generateRandomSeed} from "../../api/Constants";
import * as Popover from "@radix-ui/react-popover";
import './FilterDropdown.scss';
import {MdTune, MdRefresh, MdArrowUpward, MdArrowDownward} from "react-icons/md";
import _ from "lodash";
import React, { useEffect, useRef, useState } from "react";
import {Sort} from "../../api/Model";

const FilterDropDown = (props: { onToggleFilter: (v: boolean) => any}) => {

  const [vqParam, setVqParam]             = useUrlParam("vq", "0")
  const [sortParam, setSortParam]         = useSortParam()
  const [durationParam, setDurationParam] = useUrlParam("d", "-")
  const [uploadParam, setUploadParam]     = useUrlParam("u", "-")
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [contentWidth, setContentWidth] = useState(0)

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
              onChange      = { value => setDurationParam(rangeAsParameter(value)) }
            />
            <RadioSelectGroup
              header        = "Upload date"
              options       = { Constants.uploadOptions }
              selectedValue = { parseDurationParam(uploadParam)}
              onChange      = { value => setUploadParam(rangeAsParameter(value)) }
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

  const refreshRandom = () => {
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
        const dirIcon = selectedValue.field !== "random" && isSelected ? (
          selectedValue.direction === "asc" ? <MdArrowUpward /> : <MdArrowDownward />
        ) : undefined
        return (
          <div key={`sort-${index}`} className="filter-option" onClick={onClick}>
            <input
              type="radio"
              name="Sort"
              value={option.label}
              checked={isSelected}
              onChange={onClick}
            />
            {option.label}
            <span
              className={`sort-direction-icon${isSelected ? "" : " sort-icon-hidden"}`}
              onClick={(e) => { if (isSelected) { e.stopPropagation(); toggleDirection() } }}
              title={isSelected ? `Sort ${selectedValue.direction === "asc" ? "descending" : "ascending"}` : ""}
            >
              {dirIcon}
            </span>
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
        />
        Random
        <MdRefresh
          className={`sort-action-icon${isRandom ? "" : " sort-icon-hidden"}`}
          onClick={(e) => {
            e.stopPropagation();
            if (isRandom) refreshRandom();
          }}
          title={isRandom ? "New random order" : ""}
        />
      </div>
    </div>
  );
};

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