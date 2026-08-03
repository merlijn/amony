import { ReactNode } from "react";
import './DialogWindow.scss';

const DialogWindow = (props: { title?: string, children: ReactNode}) => {
  return(
    <div className="modal-dialog-container">
      { props.title && <div className="modal-dialog-title">{ props.title }</div> }
      <div className = "modal-dialog-content">
        { props.children }
      </div>
    </div>);
}

export default DialogWindow