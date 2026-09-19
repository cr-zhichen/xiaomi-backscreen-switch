package cn.zgccrui.backscreen;

import android.os.Bundle;

interface IDisplayService {
    Bundle query(int displayId) = 0;
    Bundle setEnabled(int displayId, boolean enabled) = 1;
    void destroy() = 16777114;
}
