import { hilog } from '@kit.PerformanceAnalysisKit';
import { ArkUIViewController, Compose } from 'compose';
import dengying from 'libentry.so';

const DOMAIN = 0x0000;
const TAG = 'DY/Compose';

@Entry
@Component
struct ComposePage {
  private controller: ArkUIViewController | undefined = undefined;
  @State errorMessage: string = '';
  // 播放画面：全屏 XComponent 铺在 Compose 下方，surfaceId 经 NAPI 交给 native AVPlayer。
  //
  // BLOCKER（2026-11-13，未修）：XComponent 在 Stack 里位于 Compose 之下，而 CMP 的
  // Compose 组件自身也是 XComponent/EGL 出图，会把视频层整块盖住 → 音频正常、画面全黑。
  // Kotlin 侧 PlayerScreen/InlinePlayer 还铺了黑色底，透明占位无法透出。
  // 也不能简单把 SURFACE 盖到上层（会遮住全部 UI）。
  // 需要 Compose↔ArkUI interop：把 native surface 放到 VideoSurface 的坐标/裁剪内，
  // 或 Compose 侧对播放区域打洞并调整层序。详见 docs/ohos-player-notes.md。
  // 位置限制同上（当前尚未做 Compose interop 定位）。
  private surfaceController: XComponentController = new XComponentController();
  private surfaceId: string = '';

  aboutToAppear(): void {
    try {
      this.controller = dengying.MainArkUIViewController();
      hilog.warn(DOMAIN, TAG, 'controller created %{public}s',
        this.controller === undefined ? 'null' : 'ok');
    } catch (e) {
      this.errorMessage = JSON.stringify(e);
      hilog.error(DOMAIN, TAG, 'controller create failed: %{public}s', this.errorMessage);
    }
  }

  build(): void {
    // Stack 的绘制顺序：先 XComponent（视频层），再 Compose（UI 层）。
    // 播放页的 VideoSurface 在 surface 就绪后透明，视频从下层透出；未就绪时
    // Kotlin 侧画黑底。Compose 中其余不透明区域会遮住视频 —— 当前全屏
    // XComponent 只能保证「播放页铺满」这一种版式，内嵌小窗需后续 interop。
    Stack({ alignContent: Alignment.TopStart }) {
      XComponent({
        id: 'dy_video_surface',
        type: XComponentType.SURFACE,
        controller: this.surfaceController
      })
        .onLoad(() => {
          try {
            this.surfaceId = this.surfaceController.getXComponentSurfaceId();
            dengying.setVideoSurfaceId(this.surfaceId);
            hilog.warn(DOMAIN, TAG, 'surface ready: %{public}s', this.surfaceId);
          } catch (e) {
            hilog.error(DOMAIN, TAG, 'surface onLoad failed: %{public}s', JSON.stringify(e));
          }
        })
        .onDestroy(() => {
          try {
            if (this.surfaceId !== '') {
              dengying.clearVideoSurfaceId(this.surfaceId);
            }
          } catch (e) {
            hilog.warn(DOMAIN, TAG, 'surface onDestroy failed: %{public}s', JSON.stringify(e));
          }
          this.surfaceId = '';
        })
        .width('100%')
        .height('100%')

      Column() {
        if (this.controller !== undefined) {
          Compose({
            // pageId 必须与 Kotlin 侧已注册的控制器 id 完全一致：
            // CMP 的 OhosComposeControllerIdContracts 只接受 "Compose_<n>" 形式，
            // 而 har 在 pageId 缺省时会自行拼 "oh_canvas_<n>"，导致 XComponentHolder
            // 用 oh_canvas_0 找不到 render（CMP.ControllerRegistry.IdRejected reason=invalid）。
            pageId: 'Compose_0',
            controller: this.controller,
            libraryName: 'entry',
            onBackPressed: () => this.controller !== undefined ? this.controller.onBackPress() : false
          })
        } else {
          Text(this.errorMessage === '' ? 'controller pending' : this.errorMessage)
            .fontSize(16)
            .fontColor(Color.Red)
        }
      }
      .width('100%')
      .height('100%')
    }
    .width('100%')
    .height('100%')
  }

  // har 的 onPageShow 内部会调 getWindowDecorVisible 等窗口能力 API；模拟器/部分
  // 设备不支持这些能力时会抛异常。若不在这里 try/catch，异常会作为 pending
  // exception 挂在 napi_env 上，之后所有 napi_call_function（含 fusion 渲染的
  // tsfn 重绘回调）都会静默失败 —— 实测表现为 notifyRedraw 每帧调用但 draw 0 次。
  onPageShow(): void {
    if (this.controller !== undefined) {
      try {
        this.controller.onPageShow();
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'onPageShow threw: %{public}s', JSON.stringify(e));
      }
    }
  }

  onPageHide(): void {
    if (this.controller !== undefined) {
      try {
        this.controller.onPageHide();
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'onPageHide threw: %{public}s', JSON.stringify(e));
      }
    }
  }

  onBackPress(): boolean {
    if (this.controller === undefined) {
      return false;
    }
    try {
      return this.controller.onBackPress();
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'onBackPress threw: %{public}s', JSON.stringify(e));
      return false;
    }
  }
}
