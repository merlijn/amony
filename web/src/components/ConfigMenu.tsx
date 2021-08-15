import React from "react";
import './ConfigMenu.scss';
import Form from "react-bootstrap/Form";
import {useCookiePrefs} from "../api/Util";
import {defaultPrefs, Prefs} from "../api/Model";
import DropDownIcon from "./shared/DropDownIcon";

const ConfigMenu = () => {

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)

  const columns = [1, 2, 3, 4, 5, 6, 7].map((v) => {
    return { value: v, label: v.toString() }
  })
  columns.unshift({ value: 0, label: "auto"})

  return(

    <DropDownIcon iconSrc="/settings_black_24dp.svg"
                  alignRight={true}
                  buttonClassName="mr-sm-1 config-menu-button"
                  contentClassName="config-menu">

      <div className="ml-sm-2 config-form">

        <Form.Group className="form-section">
          <Form.Label className="form-label">Number of columns</Form.Label>
          <div className="form-content">
            {
              columns.map((v) => {
                return <Form.Check
                  style={ { float: "left" } }
                  className="mr-sm-1"
                  name="ncols"
                  type="radio"
                  value={v.value}
                  label={v.label}
                  checked={prefs.gallery_columns === v.value}
                  onChange={(e) => {
                    setPrefs( { ...prefs, gallery_columns: v.value })
                  }
                  } />;
              })
            }
          </div>
        </Form.Group>

        <Form.Group className="form-section">
          <Form.Label className="form-label">Show video titles</Form.Label>
          <div className="form-content">
            <Form.Check
              type="checkbox"
              checked={ prefs.showTitles }
              onChange={(e) => {
                setPrefs( { ...prefs, showTitles: !prefs.showTitles })
              }
              }
            />
          </div>
        </Form.Group>


        <Form.Group className="form-section">
          <Form.Label className="form-label">Show duration</Form.Label>
          <div className="form-content">
            <Form.Check
              type="checkbox"
              checked={ prefs.showDuration }
              onChange={(e) => {
                setPrefs( { ...prefs, showDuration: !prefs.showDuration })
              }
              }
            />
          </div>
        </Form.Group>
        <Form.Group className="form-section">
          <Form.Label className="form-label">Show triple dot menu</Form.Label>
          <div className="form-content">
            <Form.Check
              type="checkbox"
              checked={ prefs.showMenu }
              onChange={(e) => {
                setPrefs( { ...prefs, showMenu: !prefs.showMenu })
              }
              }
            />
          </div>
        </Form.Group>
      </div>
    </DropDownIcon>
  )
}

export default ConfigMenu