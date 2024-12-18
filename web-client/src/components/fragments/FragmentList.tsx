import {Resource} from "../../api/Model";
import React, {CSSProperties, useState} from "react";
import FragmentPreview from "./FragmentPreview";
import './FragmentList.scss';
import ImgWithAlt from "../common/ImgWithAlt";
import { EditFragment } from "../../pages/Editor";
import { FiPlusCircle } from "react-icons/fi"

const FragmentList = (props: {vid: Resource, selected: number, selectFn: (f: EditFragment) => any, setVid: (vid: Resource) => any}) => {

  const ratio = (props.vid.resourceMeta.width / props.vid.resourceMeta.height).toFixed(2);
  const [showAddFragment, setShowAddFragment] = useState(false)

  const extraStyle = (idx: number): CSSProperties => {

    if (props.selected >=0 && idx === props.selected - 1)
      return { marginBottom: 0 }
    else if (props.selected >=0 && idx === props.selected + 1)
      return { marginTop: 0 }
    else
      return { };
  }

  const fragmentList =
    props.vid.clips.map((f, idx) => {
      return (
        <FragmentPreview
          key={ f.urls[0] }
          mediaId={ props.vid.resourceId }
          fragment = { props.vid.clips[idx] }
          style={ extraStyle(idx) }
          className = { (props.selected === idx ? "fragment-selected" : "fragment-not-selected") + " fragment" }
          showDeleteButton = { props.vid.clips.length > 1 }
          onDelete = { (v) => props.setVid(v) }
          onClick = { () => props.selectFn({ idx: idx, start: f.range[0] / 1000, end: f.range[1] / 1000 }) }
        />);
    })

  const sizing = {
    height: `calc(20vw * 1 / ${ratio})`,
    lineHeight: `calc(20vw * 1 / ${ratio})`
  }

  const nrOfFragments = props.vid.clips.length

  const addFragment =
    <div key={`fragment-${props.vid.resourceId}-new`}
         style={ extraStyle(nrOfFragments) }
         className={ (props.selected === nrOfFragments ? "fragment-selected" : "fragment-not-selected") + " fragment" }
         onClick={(e) => { props.selectFn({ idx: nrOfFragments }) } }>
      <div className="delete-fragment-icon">
        <ImgWithAlt onClick={ (e) => setShowAddFragment(false)} src="/icons/cancel.svg" />
      </div>
      <div style= { sizing } className="new-fragment">&lt;new&gt;</div>
    </div>

  const fragmentsHeader =
    <div key="fragments-header" className = "fragments-header">
      <div>Fragments</div>
    
      <FiPlusCircle className="ml-2 action-icon-medium"
                  onClick = {(e) => { setShowAddFragment(true); props.selectFn({ idx: nrOfFragments }) } } />
    </div>

  return (
    <div>
      { fragmentsHeader }
      { fragmentList }
      { showAddFragment && addFragment }
    </div>
  );
}

export default FragmentList
