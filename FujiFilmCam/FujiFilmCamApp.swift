import SwiftUI

@main
struct FujiFilmCamApp: App {
    var body: some Scene {
        WindowGroup {
            CameraScreen()
                .statusBarHidden()
                .persistentSystemOverlays(.hidden)
        }
    }
}
