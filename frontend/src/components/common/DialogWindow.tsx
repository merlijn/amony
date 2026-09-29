import { ReactNode } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { MdClose } from "react-icons/md";
import './DialogWindow.scss';

const DialogWindow = (props: { title?: string, showClose?: boolean, children: ReactNode}) => {
  const showClose = props.showClose ?? true

  return(
    <div className="modal-dialog-container">
      { props.title && <div className="modal-dialog-title">{ props.title }</div> }
      <div className = "modal-dialog-content">
        { props.children }
      </div>
      { showClose && (
        <Dialog.Close className="modal-dialog-close" aria-label="Close" title="Close">
          <MdClose />
        </Dialog.Close>
      ) }
    </div>);
}

export default DialogWindow
