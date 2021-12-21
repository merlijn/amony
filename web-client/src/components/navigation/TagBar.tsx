import _ from "lodash";
import { ReactNode, useEffect, useState } from "react";
import { MdSort, MdCropSquare } from "react-icons/md";
import { useHistory, useLocation } from "react-router-dom";
import { Api } from "../../api/Api";
import { Constants } from "../../api/Constants";
import { Prefs } from "../../api/Model";
import { useCookiePrefs } from "../../api/ReactUtils";
import { copyParams, buildUrl } from "../../api/Util";
import { DropDown, MenuItem } from "../common/DropDown";
import './TagBar.scss';

const TagBar = () => {

  const location = useLocation();
  const history = useHistory();

  const [selectedTag, setSelectedTag] = useState<string | undefined>(undefined)
  const [tags, setTags] = useState<Array<string>>([])

  const [prefs, updatePrefs] = useCookiePrefs<Prefs>("prefs/v1", "/", Constants.defaultPreferences)

  useEffect(() => {
    Api.getTags().then((updatedTags) => { setTags(updatedTags as Array<string>) })
  }, [])

  useEffect(() => {
    setSelectedTag(new URLSearchParams(location.search).get("tag") || undefined)
  }, [location]);

  const selectTag = (tag: string) => {
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)

    if (tag === selectedTag)
      newParams.delete("tag")
    else
      newParams.set("tag", tag)

    history.push(buildUrl("/search", newParams ));
  };

  return (
    <div className="tag-bar">
      <div className="tags">

        <DropDownSelect
          toggle   = { <MdSort /> }
          options  = { Constants.sortOptions }
          selected = { prefs.sort }
          onSelect = { (v) => updatePrefs({...prefs, sort: v}) } />

        <DropDownSelect
          toggle   = { <MdCropSquare /> }
          options  = { Constants.resolutions }
          selected = { prefs.videoQuality }
          onSelect = { (v) => updatePrefs({...prefs, videoQuality: v}) } />
        {
          tags.map(tag => <div className={ tag === selectedTag ? "tag selected-tag" : "tag"} onClick = {() => selectTag(tag) }>{tag}</div>)
        }
      </div>

    </div>);
}

type SelectOption<T> = {
  value: T
  label: string
  icon?: ReactNode
}

const DropDownSelect = (props:{ toggle: ReactNode, options: Array<SelectOption<any>>, selected: any, onSelect: (v: any) => void }) => {
  return (
    <DropDown hideOnClick = {true} 
      toggleIcon = { props.toggle }
      toggleClassName = "custom-dropdown-toggle" 
      contentClassName = "dropdown-menu" 
      showArrow= { true }>
      {
        props.options.map((option) => {
          return <MenuItem 
                    className={_.isEqual(option.value,props.selected) ? "menu-item-selected" : ""}  
                    onClick={() => props.onSelect(option.value)}>
                    { option.icon } <div className="menu-label-no-wrap">{ option.label }</div>
                  </MenuItem>
        })
      }
  </DropDown>)
}

export default TagBar