import React from "react";
import './SideBar.scss';

const SideBar = (props: { onHide: () => void }) => {

  const hide = () => {
    props.onHide();
  }

  return (
    <div
      className="c-modal-container"
      style={ { display: "block" }}>

      <div key="c-model-background"
           className="c-modal-background"
           onClick = { (e) => hide() }
      />

      <div key="model-content" className="c-modal-content">
        {

        }
      </div>
    </div>
  );
}

export default SideBar