import {Video} from "../../api/Model";
import React, {CSSProperties, useState} from "react";
import {EditFragment} from "./Player";
import FragmentPreview from "./FragmentPreview";
import './FragmentList.scss';

const FragmentList = (props: {vid: Video, selected: number, selectFn: (f: EditFragment) => any, setVid: (vid: Video) => any}) => {

  const ratio = (props.vid.resolution_x / props.vid.resolution_y).toFixed(2);
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
    props.vid.fragments.map((f, idx) => {
      return (
        <FragmentPreview
          vid={ props.vid.id }
          fragment = { props.vid.fragments[idx] }
          style={ extraStyle(idx) }
          className = { (props.selected == idx ? "fragment-selected" : "fragment-not-selected") + " fragment" }
          showDeleteButton= { props.vid.fragments.length > 1 }
          onDelete = { (v) => props.setVid(v) }
          onClick = { () => props.selectFn({ idx: idx, start: f.timestamp_start / 1000, end: f.timestamp_end / 1000 }) }
        />);
    })

  const sizing = {
    height: `calc(20vw * 1 / ${ratio})`,
    lineHeight: `calc(20vw * 1 / ${ratio})`
  }

  const nrOfFragments = props.vid.fragments.length

  const addFragment =
    <div key={`fragment-${props.vid.id}-new`}
         style={ extraStyle(nrOfFragments) }
         className={ (props.selected == nrOfFragments ? "fragment-selected" : "fragment-not-selected") + " fragment" }
         onClick={(e) => { props.selectFn({ idx: nrOfFragments }) } }>
      <div className="delete-fragment-icon">
        <img onClick={ (e) => setShowAddFragment(false)} src="/cancel_black_24dp.svg" />
      </div>
      <div style= { sizing } className="new-fragment">&lt;new&gt;</div>
    </div>

  const fragmentsHeader =
    <div key="fragments-header" className="fragments-header">
      Fragments
      <img style={ { float: "right" } } className="action-icon-medium" src="/close_black_24dp.svg" />
      <img style={ { float: "right" } }
           className="action-icon-medium"
           src="/add_box_black_24dp.svg"
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